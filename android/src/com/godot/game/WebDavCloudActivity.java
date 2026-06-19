package com.godot.game;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.godot.game.webdav.WebDavSettings;
import com.godot.game.webdav.WebDavSyncManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.Arrays;
import java.util.List;

public class WebDavCloudActivity extends AppCompatActivity {
	private TextView statusText;
	private TextView progressText;
	private ProgressBar progressBar;
	private LinearLayout root;
	private boolean busy;
	private SteamOperationProgressDialog operationDialog;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		buildUi();
		refreshStatus();
	}

	private void buildUi() {
		ScrollView scroll = new ScrollView(this);
		scroll.setFillViewport(false);
		scroll.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		root = ExtraSettingsUi.vertical(this);
		root.setPadding(0, ExtraSettingsUi.dp(this, 24), 0, ExtraSettingsUi.dp(this, 32));
		SystemBarInsetsHelper.applySystemBarPadding(root, true, false, false, false);
		SystemBarInsetsHelper.applySystemBarPadding(scroll, false, true, true, true);
		ExtraSettingsUi.addResponsiveScrollContent(this, scroll, root);
		setContentView(scroll);
		populateRoot();
	}

	private void populateRoot() {
		root.removeAllViews();
		root.addView(ExtraSettingsUi.title(this, R.string.webdav_cloud_title));
		ExtraSettingsUi.addCardSpacing(root, buildStatusCard());
		ExtraSettingsUi.addCardSpacing(root, buildCloudCard());
		ExtraSettingsUi.addCardSpacing(root, ExtraSettingsUi.caption(this, getString(R.string.webdav_cloud_safety_hint)));
	}

	private View buildStatusCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_cloud_sync_24, R.string.webdav_connection_title, R.string.webdav_connection_subtitle, null));
		statusText = ExtraSettingsUi.body(this, "");
		ExtraSettingsUi.addSmallSpacing(content, statusText);
		progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		progressBar.setMax(100);
		progressBar.setVisibility(View.GONE);
		ExtraSettingsUi.addSmallSpacing(content, progressBar);
		progressText = ExtraSettingsUi.caption(this, "");
		ExtraSettingsUi.addSmallSpacing(content, progressText);

		LinearLayout row1 = ExtraSettingsUi.horizontal(this);
		MaterialButton configure = ExtraSettingsUi.tonalButton(this, R.string.webdav_configure, R.drawable.ic_settings_24);
		MaterialButton test = ExtraSettingsUi.outlineButton(this, R.string.webdav_test_connection, R.drawable.ic_check_circle_24);
		configure.setOnClickListener(v -> showConfigureDialog());
		test.setOnClickListener(v -> runOperation(getString(R.string.webdav_status_testing), () -> new WebDavSyncManager(this).testConnection(this::setProgress)));
		row1.addView(configure, weighted(0));
		row1.addView(test, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row1);

		MaterialButton clear = ExtraSettingsUi.outlineButton(this, R.string.webdav_clear_credentials, R.drawable.ic_delete_24);
		clear.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_clear_credentials_confirm_title)
			.setMessage(R.string.webdav_clear_credentials_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				WebDavSettings.clear(this);
				refreshStatus();
				showMessage(getString(R.string.webdav_credentials_cleared));
			})
			.show());
		ExtraSettingsUi.addSmallSpacing(content, clear);
		return card;
	}

	private View buildCloudCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_save_24, R.string.webdav_cloud_sync_title, R.string.webdav_cloud_sync_subtitle, null));
		WebDavSyncManager.Status status = new WebDavSyncManager(this).getStatus();
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, getString(R.string.webdav_cloud_profile_status, status.profileId, status.remoteSlot, status.remoteFileCount, status.hasBaseline ? getString(R.string.yes) : getString(R.string.no))));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(this, status.accountRoot.getAbsolutePath()));

		LinearLayout modeRow = ExtraSettingsUi.horizontal(this);
		modeRow.addView(ExtraSettingsUi.body(this, R.string.webdav_cloud_mode_title), weighted(0));
		Spinner modeSpinner = new Spinner(this);
		List<String> labels = Arrays.asList(
			getString(R.string.webdav_cloud_mode_off),
			getString(R.string.webdav_cloud_mode_manual),
			getString(R.string.webdav_cloud_mode_pull_on_launch),
			getString(R.string.webdav_cloud_mode_full_auto)
		);
		List<String> values = Arrays.asList(WebDavSettings.CLOUD_MODE_OFF, WebDavSettings.CLOUD_MODE_MANUAL, WebDavSettings.CLOUD_MODE_PULL_ON_LAUNCH, WebDavSettings.CLOUD_MODE_FULL_AUTO);
		ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		modeSpinner.setAdapter(adapter);
		modeSpinner.setSelection(Math.max(0, values.indexOf(status.config.cloudMode)), false);
		modeSpinner.post(() -> modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				WebDavSettings.setCloudMode(WebDavCloudActivity.this, values.get(position));
				refreshStatusOnly();
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {}
		}));
		modeRow.addView(modeSpinner, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, modeRow);

		MaterialSwitch settingsSwitch = new MaterialSwitch(this);
		settingsSwitch.setText(R.string.webdav_cloud_sync_settings_save);
		settingsSwitch.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		settingsSwitch.setChecked(status.config.syncSettingsSave);
		settingsSwitch.setOnCheckedChangeListener((button, checked) -> WebDavSettings.setSyncSettingsSave(this, checked));
		ExtraSettingsUi.addSmallSpacing(content, settingsSwitch);

		LinearLayout row1 = ExtraSettingsUi.horizontal(this);
		MaterialButton refresh = ExtraSettingsUi.outlineButton(this, R.string.webdav_cloud_refresh, R.drawable.ic_sync_24);
		MaterialButton pull = ExtraSettingsUi.tonalButton(this, R.string.webdav_cloud_pull, R.drawable.ic_download_24);
		refresh.setOnClickListener(v -> runCloudOperation(operation -> operation.refreshManifest(this::setProgress)));
		pull.setOnClickListener(v -> confirmCloudOverwrite());
		row1.addView(refresh, weighted(0));
		row1.addView(pull, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row1);

		LinearLayout row2 = ExtraSettingsUi.horizontal(this);
		MaterialButton push = ExtraSettingsUi.outlineButton(this, R.string.webdav_cloud_push, R.drawable.ic_upload_file_24);
		MaterialButton forcePush = ExtraSettingsUi.outlineButton(this, R.string.webdav_cloud_force_push, R.drawable.ic_upload_file_24);
		push.setOnClickListener(v -> runCloudOperationWithConflictPrompt(operation -> operation.pushLocalChanges(false, this::setProgress)));
		forcePush.setOnClickListener(v -> confirmForcePush());
		row2.addView(push, weighted(0));
		row2.addView(forcePush, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row2);
		return card;
	}

	private void showConfigureDialog() {
		WebDavSettings.Config config = WebDavSettings.readConfig(this);
		LinearLayout content = ExtraSettingsUi.vertical(this);
		int padding = ExtraSettingsUi.dp(this, 8);
		content.setPadding(padding, padding, padding, 0);
		EditText url = new EditText(this);
		url.setHint(R.string.webdav_url_hint);
		url.setSingleLine(true);
		url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		url.setText(config.baseUrl);
		EditText username = new EditText(this);
		username.setHint(R.string.webdav_username_hint);
		username.setSingleLine(true);
		username.setText(config.username);
		EditText password = new EditText(this);
		password.setHint(R.string.webdav_password_hint);
		password.setSingleLine(true);
		password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		password.setText(config.password);
		EditText slot = new EditText(this);
		slot.setHint(R.string.webdav_remote_slot_hint);
		slot.setSingleLine(true);
		slot.setText(config.remoteSlot);
		content.addView(url);
		content.addView(username);
		content.addView(password);
		content.addView(slot);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_configure)
			.setMessage(R.string.webdav_configure_message)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.webdav_save_config, (dialog, which) -> {
				WebDavSettings.saveConnection(this,
					url.getText() == null ? "" : url.getText().toString(),
					username.getText() == null ? "" : username.getText().toString(),
					password.getText() == null ? "" : password.getText().toString(),
					slot.getText() == null ? "" : slot.getText().toString());
				refreshStatus();
				showMessage(getString(R.string.webdav_config_saved));
			})
			.show();
	}

	private void confirmCloudOverwrite() {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_cloud_pull_confirm_title)
			.setMessage(R.string.webdav_cloud_pull_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runCloudOperationWithConflictPrompt(operation -> operation.pullAll(this::setProgress)))
			.show();
	}

	private void confirmForcePush() {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_cloud_force_push_confirm_title)
			.setMessage(R.string.webdav_cloud_force_push_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runCloudOperation(operation -> operation.pushLocalChanges(true, this::setProgress)))
			.show();
	}

	private void runCloudOperation(CloudOperation operation) {
		runOperation(getString(R.string.webdav_status_cloud_busy), () -> operation.run(new WebDavSyncManager(this)));
	}

	private void runCloudOperationWithConflictPrompt(CloudOperation operation) {
		if (busy) {
			return;
		}
		String busyMessage = getString(R.string.webdav_status_cloud_busy);
		busy = true;
		showOperationDialog(busyMessage);
		setProgress(0, busyMessage);
		new Thread(() -> {
			try {
				String result = operation.run(new WebDavSyncManager(this));
				runOnUiThread(() -> {
					busy = false;
					setProgress(100, result);
					dismissOperationDialog();
					refreshStatus();
					showMessage(result);
				});
			} catch (Exception exception) {
				WebDavSyncManager.CloudConflictException conflict = findCloudConflict(exception);
				runOnUiThread(() -> {
					busy = false;
					dismissOperationDialog();
					progressBar.setVisibility(View.GONE);
					refreshStatusOnly();
					if (conflict != null) {
						showCloudConflictDialog(conflict);
					} else {
						progressText.setText(exception.getMessage() == null ? exception.toString() : exception.getMessage());
						showMessage(getString(R.string.error_operation_failed) + ": " + (exception.getMessage() == null ? exception.toString() : exception.getMessage()));
					}
				});
			}
		}, "sts2-webdav-cloud-operation").start();
	}

	private void showCloudConflictDialog(WebDavSyncManager.CloudConflictException conflict) {
		String message = getString(
			R.string.webdav_cloud_conflict_message,
			conflict.getConflictCount(),
			conflict.getConflictSummary(8)
		);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_cloud_conflict_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.webdav_cloud_conflict_keep_cloud, (dialog, which) -> runCloudOperation(operation -> operation.pullAll(true, this::setProgress)))
			.setPositiveButton(R.string.webdav_cloud_conflict_keep_local, (dialog, which) -> runCloudOperation(operation -> operation.pushLocalChanges(true, this::setProgress)))
			.show();
	}

	private WebDavSyncManager.CloudConflictException findCloudConflict(Throwable exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof WebDavSyncManager.CloudConflictException) {
				return (WebDavSyncManager.CloudConflictException) current;
			}
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
		}
		return null;
	}

	private void runOperation(String busyMessage, ThrowingSupplier operation) {
		if (busy) {
			return;
		}
		busy = true;
		showOperationDialog(busyMessage);
		setProgress(0, busyMessage);
		new Thread(() -> {
			try {
				String result = operation.run();
				runOnUiThread(() -> {
					busy = false;
					setProgress(100, result);
					dismissOperationDialog();
					refreshStatus();
					showMessage(result);
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					dismissOperationDialog();
					progressBar.setVisibility(View.GONE);
					progressText.setText(exception.getMessage() == null ? exception.toString() : exception.getMessage());
					refreshStatusOnly();
					showMessage(getString(R.string.error_operation_failed) + ": " + (exception.getMessage() == null ? exception.toString() : exception.getMessage()));
				});
			}
		}, "sts2-webdav-operation").start();
	}

	private void showOperationDialog(String message) {
		dismissOperationDialog();
		operationDialog = new SteamOperationProgressDialog(this, getString(R.string.webdav_operation_progress_title), message);
		operationDialog.show();
	}

	private void dismissOperationDialog() {
		if (operationDialog != null) {
			operationDialog.dismiss();
			operationDialog = null;
		}
	}

	private void setProgress(int percent, String message) {
		runOnUiThread(() -> {
			progressBar.setVisibility(View.VISIBLE);
			progressBar.setIndeterminate(percent < 0);
			if (percent >= 0) {
				progressBar.setProgress(Math.max(0, Math.min(100, percent)));
			}
			progressText.setText(message == null ? "" : message);
			if (operationDialog != null) {
				operationDialog.setProgress(percent, message);
			}
		});
	}

	private void refreshStatus() {
		populateRoot();
		refreshStatusOnly();
	}

	private void refreshStatusOnly() {
		if (statusText == null) {
			return;
		}
		WebDavSettings.Config config = WebDavSettings.readConfig(this);
		String url = config.isConfigured() ? config.baseUrl : getString(R.string.webdav_not_configured);
		String user = TextUtils.isEmpty(config.username) ? getString(R.string.webdav_username_anonymous) : config.username;
		statusText.setText(getString(R.string.webdav_connection_status_format, url, user, config.remoteSlot, config.cloudMode, config.lastError));
	}

	private void showMessage(String message) {
		Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
	}

	private LinearLayout.LayoutParams weighted(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(this, marginStartDp));
		return params;
	}

	private interface ThrowingSupplier { String run() throws Exception; }
	private interface CloudOperation { String run(WebDavSyncManager operation) throws Exception; }
}
