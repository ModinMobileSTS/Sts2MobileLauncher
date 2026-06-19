package com.godot.game;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

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
	private static final long MAX_PREVIEW_BYTES = 1024L * 1024L;
	private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
	private static final String ROOT_INTERNAL_ARCHIVE = "internal-files";
	private static final String ROOT_EXTERNAL_ARCHIVE = "external-files";

	private final List<LogEntry> logEntries = new ArrayList<>();
	private final LinkedHashSet<Integer> selectedPositions = new LinkedHashSet<>();

	private TextView emptyListText;
	private RecyclerView logsRecyclerView;
	private LogAdapter adapter;

	private ActionMode selectionActionMode;
	private int selectionAnchorPosition = RecyclerView.NO_POSITION;
	private int lastInteractedPosition = RecyclerView.NO_POSITION;
	private boolean refreshing;
	private List<LogEntry> pendingExportEntries = Collections.emptyList();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_log_viewer);

		bindViews();
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setTitle(R.string.log_viewer_title);
			getSupportActionBar().setSubtitle(getString(R.string.log_viewer_status_loading));
		}
		AppBarContentOverlapHelper.install(this);

		adapter = new LogAdapter();
		logsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
		logsRecyclerView.setAdapter(adapter);

		refreshLogs();
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
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

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_log_viewer, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem refreshItem = menu.findItem(R.id.action_refresh_logs);
		if (refreshItem != null) {
			refreshItem.setEnabled(!refreshing);
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_refresh_logs) {
			refreshLogs();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void bindViews() {
		emptyListText = findViewById(R.id.text_empty_logs);
		logsRecyclerView = findViewById(R.id.recycler_logs);
	}

	private void refreshLogs() {
		if (refreshing) {
			return;
		}
		refreshing = true;
		updateSubtitle(getString(R.string.log_viewer_status_loading));
		clearSelection();
		supportInvalidateOptionsMenu();
		new Thread(() -> {
			List<LogEntry> entries = scanLogEntries();
			runOnUiThread(() -> applyRefreshedLogs(entries));
		}).start();
	}

	private void applyRefreshedLogs(List<LogEntry> entries) {
		refreshing = false;
		logEntries.clear();
		logEntries.addAll(entries);
		adapter.notifyDataSetChanged();
		updateSummary();
		updateEmptyListVisibility();
		supportInvalidateOptionsMenu();
	}

	private void updateSummary() {
		if (logEntries.isEmpty()) {
			updateSubtitle(getString(R.string.log_viewer_summary_empty));
			return;
		}
		LogEntry newestEntry = logEntries.get(0);
		updateSubtitle(getString(R.string.log_viewer_summary_count, logEntries.size(), formatDate(newestEntry.lastModified)));
	}

	private void updateEmptyListVisibility() {
		boolean empty = logEntries.isEmpty();
		emptyListText.setVisibility(empty ? View.VISIBLE : View.GONE);
		logsRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
	}

	private void updateSubtitle(CharSequence subtitle) {
		if (getSupportActionBar() != null) {
			getSupportActionBar().setSubtitle(subtitle);
		}
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
		String normalizedPath = normalizeRelativePath(relativePath).toLowerCase(Locale.ROOT);
		String lowerFileName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
		if (normalizedPath.startsWith("sentry/reports/") || normalizedPath.contains("/sentry/reports/")) {
			return getString(R.string.log_viewer_source_sentry);
		}
		if ("monomod-harmony.log".equals(lowerFileName)) {
			return getString(R.string.log_viewer_source_harmony);
		}
		if ("console_history.log".equals(lowerFileName)) {
			return getString(R.string.log_viewer_source_console);
		}
		if (normalizedPath.startsWith("logs/") || normalizedPath.contains("/logs/")) {
			return getString(R.string.log_viewer_source_runtime);
		}
		return getString(R.string.log_viewer_source_other);
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
		if (position < 0 || position >= logEntries.size()) {
			return;
		}
		lastInteractedPosition = position;
		if (selectionActionMode != null) {
			toggleSelection(position);
			return;
		}
		openLogDetail(logEntries.get(position));
	}

	private boolean onLogLongPressed(int position) {
		if (position < 0 || position >= logEntries.size()) {
			return false;
		}
		lastInteractedPosition = position;
		if (selectionActionMode == null) {
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
	}

	private void updateSelectionActionMode() {
		if (selectionActionMode == null) {
			return;
		}
		selectionActionMode.setTitle(getString(R.string.log_viewer_selection_title, selectedPositions.size()));
	}

	private void clearSelection() {
		if (selectionActionMode != null) {
			selectionActionMode.finish();
			return;
		}
		if (selectedPositions.isEmpty()) {
			selectionAnchorPosition = RecyclerView.NO_POSITION;
			lastInteractedPosition = RecyclerView.NO_POSITION;
			return;
		}
		selectedPositions.clear();
		selectionAnchorPosition = RecyclerView.NO_POSITION;
		lastInteractedPosition = RecyclerView.NO_POSITION;
		adapter.notifyDataSetChanged();
	}

	private List<LogEntry> getSelectedEntries() {
		List<LogEntry> entries = new ArrayList<>();
		for (int i = 0; i < logEntries.size(); i++) {
			if (selectedPositions.contains(i)) {
				entries.add(logEntries.get(i));
			}
		}
		return entries;
	}

	private void selectAllLogs() {
		if (logEntries.isEmpty()) {
			return;
		}
		selectedPositions.clear();
		for (int i = 0; i < logEntries.size(); i++) {
			selectedPositions.add(i);
		}
		if (selectionAnchorPosition == RecyclerView.NO_POSITION) {
			selectionAnchorPosition = 0;
		}
		lastInteractedPosition = logEntries.size() - 1;
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
		if (entries.isEmpty()) {
			return;
		}
		new Thread(() -> {
			try {
				File sharedDirectory = new File(getCacheDir(), "shared");
				ensureDirectory(sharedDirectory);
				File zipFile = new File(sharedDirectory, buildDefaultLogExportName());
				if (zipFile.exists() && !zipFile.delete()) {
					throw new IOException("Unable to replace existing shared ZIP: " + zipFile.getAbsolutePath());
				}
				try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(zipFile))) {
					writeLogsZip(outputStream, entries);
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
		String message = getString(R.string.error_operation_failed) + ": " + detail;
		showSnackbar(message, Snackbar.LENGTH_LONG);
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
			getMenuInflater().inflate(R.menu.menu_log_selection, menu);
			return true;
		}

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			int itemId = item.getItemId();
			if (itemId == R.id.action_select_range) {
				selectRange();
				return true;
			}
			if (itemId == R.id.action_select_all) {
				selectAllLogs();
				return true;
			}
			if (itemId == R.id.action_copy_logs) {
				copySelectedLogs();
				return true;
			}
			if (itemId == R.id.action_share_logs) {
				shareSelectedLogs();
				return true;
			}
			if (itemId == R.id.action_export_logs) {
				exportSelectedLogs();
				return true;
			}
			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			selectionActionMode = null;
			selectedPositions.clear();
			selectionAnchorPosition = RecyclerView.NO_POSITION;
			lastInteractedPosition = RecyclerView.NO_POSITION;
			adapter.notifyDataSetChanged();
		}
	};

	private final class LogAdapter extends RecyclerView.Adapter<LogViewHolder> {
		@Override
		public LogViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log_entry, parent, false);
			return new LogViewHolder(view);
		}

		@Override
		public void onBindViewHolder(LogViewHolder holder, int position) {
			LogEntry entry = logEntries.get(position);
			boolean selected = selectedPositions.contains(position);
			holder.bind(entry, selected);
		}

		@Override
		public int getItemCount() {
			return logEntries.size();
		}
	}

	private final class LogViewHolder extends RecyclerView.ViewHolder {
		private final View container;
		private final TextView nameText;
		private final TextView sourceBadgeText;
		private final TextView pathText;
		private final TextView metaText;
		private final TextView iconText;

		LogViewHolder(View itemView) {
			super(itemView);
			container = itemView.findViewById(R.id.log_row_container);
			nameText = itemView.findViewById(R.id.text_log_name);
			sourceBadgeText = itemView.findViewById(R.id.text_log_source);
			pathText = itemView.findViewById(R.id.text_log_path);
			metaText = itemView.findViewById(R.id.text_log_meta);
			iconText = itemView.findViewById(R.id.text_log_icon);
			itemView.setOnClickListener(v -> {
				int position = getBindingAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					onLogClicked(position);
				}
			});
			itemView.setOnLongClickListener(v -> {
				int position = getBindingAdapterPosition();
				return position != RecyclerView.NO_POSITION && onLogLongPressed(position);
			});
		}

		void bind(LogEntry entry, boolean selected) {
			nameText.setText(entry.file.getName());
			sourceBadgeText.setText(entry.sourceLabel);
			pathText.setText(entry.displayPath);
			metaText.setText(formatDate(entry.lastModified) + " · " + Formatter.formatFileSize(LogViewerActivity.this, entry.size));
			iconText.setText(selected ? "✓" : "L");
			if (selected) {
				container.setBackgroundColor(0xFF2B3762);
				nameText.setTextColor(0xFFDCE2FF);
				pathText.setTextColor(0xFFDCE2FF);
				metaText.setTextColor(0xFFDCE2FF);
				iconText.setTextColor(0xFFDCE2FF);
			} else {
				container.setBackgroundColor(0x00000000);
				nameText.setTextColor(0xFFF0F0F8);
				pathText.setTextColor(0xFFC5C7D3);
				metaText.setTextColor(0xFFC5C7D3);
				iconText.setTextColor(0xFFB7C4FF);
			}
		}
	}

	private static final class LogEntry {
		final File file;
		final String displayPath;
		final String archivePath;
		final String sourceLabel;
		final long lastModified;
		final long size;

		LogEntry(File file, String displayPath, String archivePath, String sourceLabel, long lastModified, long size) {
			this.file = file;
			this.displayPath = displayPath;
			this.archivePath = archivePath;
			this.sourceLabel = sourceLabel;
			this.lastModified = lastModified;
			this.size = size;
		}
	}
}
