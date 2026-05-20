package com.godot.game;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TextEditorActivity extends AppCompatActivity {
	public static final String EXTRA_FILE_PATH = "com.godot.game.extra.FILE_PATH";
	public static final String EXTRA_ROOT_PATH = "com.godot.game.extra.ROOT_PATH";

	private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

	private TextView titleText;
	private TextView metaText;
	private EditText contentInput;

	private File targetFile;
	private File rootDirectory;
	private String originalContent = "";
	private boolean suppressTextChanges;
	private boolean loading;
	private boolean working;
	private boolean editorReady;
	private boolean dirty;
	private boolean pendingReloadAfterExternalEdit;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_text_editor);

		bindViews();
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setTitle(R.string.text_editor_title);
		}
		AppBarContentOverlapHelper.install(this);

		String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
		String rootPath = getIntent().getStringExtra(EXTRA_ROOT_PATH);
		targetFile = TextUtils.isEmpty(filePath) ? null : new File(filePath);
		rootDirectory = TextUtils.isEmpty(rootPath) ? null : new File(rootPath);
		if (rootDirectory == null && targetFile != null) {
			rootDirectory = targetFile.getParentFile();
		}

		contentInput.setEnabled(false);
		contentInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable editable) {
				if (suppressTextChanges) {
					return;
				}
				dirty = editorReady && !working && !TextUtils.equals(originalContent, editable == null ? "" : editable.toString());
				updateUiState();
			}
		});

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				handleBackNavigation();
			}
		});

		loadFile();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (pendingReloadAfterExternalEdit && !loading && !working && !dirty) {
			pendingReloadAfterExternalEdit = false;
			loadFile();
		}
	}

	@Override
	public boolean onSupportNavigateUp() {
		handleBackNavigation();
		return true;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_text_editor, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem saveItem = menu.findItem(R.id.action_save_text);
		MenuItem externalItem = menu.findItem(R.id.action_open_external_editor);
		if (saveItem != null) {
			saveItem.setEnabled(editorReady && dirty && !loading && !working);
		}
		if (externalItem != null) {
			externalItem.setEnabled(targetFile != null && targetFile.isFile());
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_save_text) {
			saveFile();
			return true;
		}
		if (itemId == R.id.action_open_external_editor) {
			openExternalEditor();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void bindViews() {
		titleText = findViewById(R.id.text_editor_title);
		metaText = findViewById(R.id.text_editor_meta);
		contentInput = findViewById(R.id.input_editor_content);
	}

	private void loadFile() {
		loading = true;
		editorReady = false;
		dirty = false;
		updateUiState();
		new Thread(() -> {
			LoadedFileResult result = new LoadedFileResult();
			try {
				if (targetFile == null || !targetFile.isFile()) {
					throw new IllegalStateException(getString(R.string.file_browser_missing_file));
				}
				if (!FileBrowserSupport.isProbablyText(targetFile)) {
					result.previewText = getString(R.string.text_editor_binary_unsupported);
					result.editable = false;
				} else if (targetFile.length() > FileBrowserSupport.MAX_EDITABLE_TEXT_BYTES) {
					result.previewText = getString(R.string.text_editor_too_large, Formatter.formatFileSize(this, FileBrowserSupport.MAX_EDITABLE_TEXT_BYTES));
					result.editable = false;
				} else {
					result.previewText = FileBrowserSupport.readTextFile(targetFile);
					result.editable = true;
				}
			} catch (Exception exception) {
				result.previewText = getString(R.string.error_operation_failed) + ": " + buildErrorDetail(exception);
				result.editable = false;
			}
			runOnUiThread(() -> applyLoadedResult(result));
		}).start();
	}

	private void applyLoadedResult(LoadedFileResult result) {
		loading = false;
		suppressTextChanges = true;
		contentInput.setText(result.previewText == null ? "" : result.previewText);
		suppressTextChanges = false;
		contentInput.setEnabled(result.editable);
		editorReady = result.editable;
		originalContent = result.editable ? (result.previewText == null ? "" : result.previewText) : "";
		dirty = false;
		updateUiState();
	}

	private void saveFile() {
		if (!editorReady || loading || working || targetFile == null) {
			return;
		}
		String content = contentInput.getText() == null ? "" : contentInput.getText().toString();
		working = true;
		updateUiState();
		new Thread(() -> {
			try {
				FileBrowserSupport.writeTextFile(targetFile, content);
				runOnUiThread(() -> {
					working = false;
					originalContent = content;
					dirty = false;
					setResult(RESULT_OK);
					updateUiState();
					toast(getString(R.string.text_editor_saved));
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					working = false;
					updateUiState();
					showError(exception);
				});
			}
		}).start();
	}

	private void openExternalEditor() {
		if (targetFile == null || !targetFile.isFile() || loading || working) {
			return;
		}
		if (dirty) {
			toast(getString(R.string.text_editor_save_before_external));
			return;
		}
		try {
			pendingReloadAfterExternalEdit = true;
			FileBrowserSupport.openFileInExternalApp(this, targetFile, true, getString(R.string.file_browser_external_edit_chooser));
		} catch (Exception exception) {
			pendingReloadAfterExternalEdit = false;
			showError(exception);
		}
	}

	private void handleBackNavigation() {
		if (working) {
			return;
		}
		if (!dirty) {
			finish();
			return;
		}
		new AlertDialog.Builder(this)
			.setTitle(R.string.text_editor_unsaved_title)
			.setMessage(R.string.text_editor_unsaved_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.text_editor_discard, (dialog, which) -> finish())
			.show();
	}

	private void updateUiState() {
		if (targetFile != null) {
			titleText.setText(targetFile.getName());
			if (getSupportActionBar() != null) {
				getSupportActionBar().setTitle(R.string.text_editor_title);
				getSupportActionBar().setSubtitle(buildRelativePathLabel());
			}
		} else {
			titleText.setText(R.string.text_editor_title);
			if (getSupportActionBar() != null) {
				getSupportActionBar().setTitle(R.string.text_editor_title);
				getSupportActionBar().setSubtitle(getString(R.string.text_editor_subtitle));
			}
		}
		metaText.setText(buildMetaText());
		supportInvalidateOptionsMenu();
	}

	private CharSequence buildRelativePathLabel() {
		if (targetFile == null) {
			return getString(R.string.text_editor_subtitle);
		}
		if (rootDirectory == null) {
			return targetFile.getAbsolutePath();
		}
		String relativePath = FileBrowserSupport.buildRelativePath(rootDirectory, targetFile);
		if (TextUtils.isEmpty(relativePath)) {
			return targetFile.getName();
		}
		return relativePath;
	}

	private CharSequence buildMetaText() {
		if (targetFile == null) {
			return getString(R.string.text_editor_status_loading);
		}
		String relativePath = rootDirectory == null ? targetFile.getName() : FileBrowserSupport.buildRelativePath(rootDirectory, targetFile);
		if (TextUtils.isEmpty(relativePath)) {
			relativePath = targetFile.getName();
		}
		String baseMeta = getString(
			R.string.text_editor_meta_format,
			relativePath,
			formatDate(targetFile.lastModified()),
			Formatter.formatFileSize(this, targetFile.isFile() ? targetFile.length() : 0L)
		);
		if (loading) {
			return baseMeta + " · " + getString(R.string.text_editor_status_loading);
		}
		if (working) {
			return baseMeta + " · " + getString(R.string.text_editor_status_saving);
		}
		if (dirty) {
			return baseMeta + " · " + getString(R.string.text_editor_status_unsaved);
		}
		return baseMeta;
	}

	private String formatDate(long timeMillis) {
		if (timeMillis <= 0L) {
			return getString(R.string.log_file_viewer_unknown_time);
		}
		return new SimpleDateFormat(DATE_TIME_PATTERN, Locale.getDefault()).format(new Date(timeMillis));
	}

	private String buildErrorDetail(Exception exception) {
		String detail = exception.getMessage();
		if (detail == null || detail.trim().isEmpty()) {
			return exception.getClass().getSimpleName();
		}
		return detail;
	}

	private void toast(String message) {
		showSnackbar(message, Snackbar.LENGTH_SHORT);
	}

	private void showError(Exception exception) {
		showSnackbar(getString(R.string.error_operation_failed) + ": " + buildErrorDetail(exception), Snackbar.LENGTH_LONG);
	}

	private void showSnackbar(String message, int duration) {
		android.view.View anchor = findViewById(android.R.id.content);
		if (anchor != null && message != null && !message.trim().isEmpty()) {
			Snackbar.make(anchor, message, duration).show();
		}
	}

	private static final class LoadedFileResult {
		String previewText;
		boolean editable;
	}
}
