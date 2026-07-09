package com.godot.game;

import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogFileViewerActivity extends AppCompatActivity {
	public static final String EXTRA_FILE_PATH = "com.godot.game.extra.LOG_FILE_PATH";
	public static final String EXTRA_DISPLAY_NAME = "com.godot.game.extra.LOG_DISPLAY_NAME";
	public static final String EXTRA_DISPLAY_PATH = "com.godot.game.extra.LOG_DISPLAY_PATH";
	public static final String EXTRA_SOURCE_LABEL = "com.godot.game.extra.LOG_SOURCE_LABEL";
	public static final String EXTRA_LAST_MODIFIED = "com.godot.game.extra.LOG_LAST_MODIFIED";
	public static final String EXTRA_FILE_SIZE = "com.godot.game.extra.LOG_FILE_SIZE";

	private static final long MAX_PREVIEW_BYTES = 1024L * 1024L;
	private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

	private TextView titleText;
	private TextView metaText;
	private TextView contentText;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_log_file_viewer);

		MaterialToolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setTitle(R.string.log_file_viewer_title);
		}
		SystemBarInsetsHelper.applySystemBarPadding(toolbar, true, true, false, true);
		SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.content_container), false, true, true, true);

		titleText = findViewById(R.id.text_log_content_title);
		metaText = findViewById(R.id.text_log_content_meta);
		contentText = findViewById(R.id.text_log_content);

		String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
		String displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
		String displayPath = getIntent().getStringExtra(EXTRA_DISPLAY_PATH);
		String sourceLabel = getIntent().getStringExtra(EXTRA_SOURCE_LABEL);
		long lastModified = getIntent().getLongExtra(EXTRA_LAST_MODIFIED, 0L);
		long fileSize = getIntent().getLongExtra(EXTRA_FILE_SIZE, 0L);

		if (displayName == null || displayName.trim().isEmpty()) {
			displayName = getString(R.string.log_file_viewer_title);
		}
		if (getSupportActionBar() != null) {
			getSupportActionBar().setSubtitle(displayName);
		}
		titleText.setText(displayName);
		metaText.setText(getString(R.string.log_viewer_content_loading));
		contentText.setText("");

		if (filePath == null || filePath.trim().isEmpty()) {
			metaText.setText(displayPath == null ? "" : displayPath);
			contentText.setText(getString(R.string.log_file_viewer_missing));
			return;
		}

		File file = new File(filePath);
		String finalDisplayName = displayName;
		String finalDisplayPath = displayPath == null ? file.getName() : displayPath;
		String finalSourceLabel = sourceLabel == null ? getString(R.string.log_viewer_source_other) : sourceLabel;
		new Thread(() -> {
			String previewText;
			String previewMeta;
			try {
				if (!file.isFile()) {
					throw new IOException(getString(R.string.log_file_viewer_missing));
				}
				long actualLastModified = file.lastModified() > 0L ? file.lastModified() : lastModified;
				long actualFileSize = file.length() > 0L ? file.length() : fileSize;
				previewText = readPreviewText(file);
				previewMeta = finalSourceLabel
					+ " · " + formatDate(actualLastModified)
					+ " · " + Formatter.formatFileSize(this, actualFileSize)
					+ " · " + finalDisplayPath;
			} catch (Exception exception) {
				previewText = getString(R.string.error_operation_failed) + ": " + buildErrorDetail(exception);
				previewMeta = finalSourceLabel + " · " + finalDisplayPath;
			}
			String finalPreviewText = previewText;
			String finalPreviewMeta = previewMeta;
			runOnUiThread(() -> {
				titleText.setText(finalDisplayName);
				metaText.setText(finalPreviewMeta);
				contentText.setText(finalPreviewText);
			});
		}).start();
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	private String readPreviewText(File file) throws Exception {
		if (!isProbablyText(file)) {
			return getString(R.string.log_viewer_binary_unavailable);
		}
		long length = file.length();
		if (length <= MAX_PREVIEW_BYTES) {
			String content = readTextFile(file);
			return content.isEmpty() ? getString(R.string.log_viewer_content_empty) : content;
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
			if (content.isEmpty()) {
				content = getString(R.string.log_viewer_content_empty);
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

	private void copyStream(InputStream inputStream, ByteArrayOutputStream outputStream) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, read);
		}
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
}
