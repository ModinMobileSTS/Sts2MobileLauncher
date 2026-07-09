package com.godot.game;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;

import cat.ereza.customactivityoncrash.CustomActivityOnCrash;
import cat.ereza.customactivityoncrash.config.CaocConfig;

public class Sts2CrashActivity extends AppCompatActivity {
	private CaocConfig config;
	private String fullErrorDetails;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		StartupHealthTracker.clearPendingLaunchState(this);
		setContentView(R.layout.activity_sts2_crash);
		// Toolbar consumes top (status/cutout); content root consumes horizontal + bottom so children share one scaffold.
		SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.toolbar_crash), true, false, false, false);
		SystemBarInsetsHelper.applySystemBarPadding(findViewById(android.R.id.content), false, true, true, true);

		config = CustomActivityOnCrash.getConfigFromIntent(getIntent());
		fullErrorDetails = buildCompactErrorDetails();

		bindContent();
		bindActions();
	}

	private void bindContent() {
		((TextView) findViewById(R.id.text_crash_summary)).setText(buildCrashSummary());
		((TextView) findViewById(R.id.text_badge_renderer)).setText(getString(R.string.sts2_crash_badge_renderer, getRendererDisplayName()));
		((TextView) findViewById(R.id.text_badge_android)).setText(getString(R.string.sts2_crash_badge_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
		((TextView) findViewById(R.id.text_badge_build)).setText(getString(R.string.sts2_crash_badge_build, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR, BuildConfig.BUILD_TYPE));
		((TextView) findViewById(R.id.text_crash_details)).setText(fullErrorDetails);
	}

	private void bindActions() {
		findViewById(R.id.button_open_settings).setOnClickListener(v -> restartInto(createSettingsIntent()));
		findViewById(R.id.button_retry_game).setOnClickListener(v -> restartInto(GodotApp.createLaunchIntent(this, true)));
		findViewById(R.id.button_copy_details).setOnClickListener(v -> copyErrorDetails());
		findViewById(R.id.button_close_app).setOnClickListener(v -> closeApplication());
	}

	private Intent createSettingsIntent() {
		Intent intent = new Intent(this, GameSettingsActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return intent;
	}

	private void restartInto(Intent intent) {
		if (config != null) {
			CustomActivityOnCrash.restartApplicationWithIntent(this, intent, config);
			return;
		}

		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		finish();
		startActivity(intent);
		killCurrentProcess();
	}

	private void closeApplication() {
		if (config != null) {
			CustomActivityOnCrash.closeApplication(this, config);
			return;
		}
		finish();
		killCurrentProcess();
	}

	private void copyErrorDetails() {
		ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboardManager != null) {
			clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.sts2_crash_toolbar_title), fullErrorDetails));
			Snackbar.make(findViewById(android.R.id.content), R.string.sts2_crash_copied, Snackbar.LENGTH_SHORT).show();
		}
	}

	private String buildCrashSummary() {
		String stackTrace = CustomActivityOnCrash.getStackTraceFromIntent(getIntent());
		if (TextUtils.isEmpty(stackTrace)) {
			return getString(R.string.sts2_crash_summary_fallback);
		}

		String[] lines = stackTrace.replace("\r\n", "\n").split("\n");
		for (String line : lines) {
			if (!TextUtils.isEmpty(line.trim())) {
				return line.trim();
			}
		}
		return getString(R.string.sts2_crash_summary_fallback);
	}

	private String buildCompactErrorDetails() {
		String details = CustomActivityOnCrash.getAllErrorDetailsFromIntent(this, getIntent());
		if (TextUtils.isEmpty(details)) {
			details = getString(R.string.sts2_crash_detail_fallback);
		}
		return compactWhitespace(details);
	}

	private String compactWhitespace(String text) {
		String compacted = text.replace("\r\n", "\n");
		compacted = compacted.replace(" \n", "\n");
		while (compacted.contains("\n\n\n")) {
			compacted = compacted.replace("\n\n\n", "\n\n");
		}
		return compacted.trim();
	}

	private String getRendererDisplayName() {
		String renderer = RendererPreference.getSelectedRenderer(this);
		if (RendererPreference.RENDERER_OPENGL_ES3.equals(renderer)) {
			return getString(R.string.renderer_option_opengl_es3);
		}
		return getString(R.string.renderer_option_vulkan);
	}

	private static void killCurrentProcess() {
		android.os.Process.killProcess(android.os.Process.myPid());
		System.exit(10);
	}
}
