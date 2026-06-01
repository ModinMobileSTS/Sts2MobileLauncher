package com.godot.game;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.godot.game.steam.auth.SteamAuthStore;
import com.godot.game.steam.auth.SteamLoginCoordinator;
import com.godot.game.steam.cloud.Sts2SteamCloudClient;
import com.godot.game.steam.cloud.Sts2SteamCloudSyncManager;
import com.godot.game.steam.core.SteamSettings;
import com.godot.game.steam.download.Sts2SteamPayloadDownloader;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SteamAccountActivity extends AppCompatActivity {
	private static final int SAFETY_NOTICE_COUNTDOWN_SECONDS = 5;
	private static final long SAFETY_NOTICE_COUNTDOWN_INTERVAL_MS = 1000L;

	private TextView statusText;
	private TextView progressText;
	private ProgressBar progressBar;
	private LinearLayout root;
	private boolean busy;
	private SteamOperationProgressDialog operationDialog;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		buildUi();
		refreshStatus();
		showFirstOpenSafetyNoticeIfNeeded();
	}

	private void showFirstOpenSafetyNoticeIfNeeded() {
		if (!SteamSettings.hasSeenAccountSafetyNotice(this)) {
			showSafetyNoticeDialog(true);
		}
	}

	private void showSafetyNoticeDialog(boolean requireCountdown) {
		Handler countdownHandler = new Handler(Looper.getMainLooper());
		final Runnable[] countdownTick = new Runnable[1];
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_account_safety_notice_title)
			.setMessage(Html.fromHtml(getString(R.string.steam_account_safety_notice_message), Html.FROM_HTML_MODE_LEGACY))
			.setCancelable(!requireCountdown);
		if (requireCountdown) {
			builder.setPositiveButton(getString(R.string.steam_account_safety_notice_wait_button, SAFETY_NOTICE_COUNTDOWN_SECONDS), null);
		} else {
			builder.setPositiveButton(R.string.steam_account_safety_notice_ack_button, null);
		}
		AlertDialog dialog = builder.create();
		dialog.setOnShowListener(shown -> {
			if (!requireCountdown) {
				return;
			}
			dialog.setCanceledOnTouchOutside(false);
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
				SteamSettings.markAccountSafetyNoticeSeen(this);
				dialog.dismiss();
			});
			countdownTick[0] = new Runnable() {
				private int remainingSeconds = SAFETY_NOTICE_COUNTDOWN_SECONDS;

				@Override
				public void run() {
					if (isFinishing() || isDestroyed() || !dialog.isShowing()) {
						return;
					}
					remainingSeconds--;
					if (remainingSeconds <= 0) {
						dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(R.string.steam_account_safety_notice_ack_button);
						dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
						return;
					}
					dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(getString(R.string.steam_account_safety_notice_wait_button, remainingSeconds));
					countdownHandler.postDelayed(this, SAFETY_NOTICE_COUNTDOWN_INTERVAL_MS);
				}
			};
			countdownHandler.postDelayed(countdownTick[0], SAFETY_NOTICE_COUNTDOWN_INTERVAL_MS);
		});
		dialog.setOnDismissListener(dismissed -> {
			if (countdownTick[0] != null) {
				countdownHandler.removeCallbacks(countdownTick[0]);
			}
		});
		dialog.show();
	}

	private void buildUi() {
		ScrollView scroll = new ScrollView(this);
		scroll.setFillViewport(false);
		scroll.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		root = ExtraSettingsUi.vertical(this);
		int padding = ExtraSettingsUi.dp(this, 20);
		root.setPadding(padding, ExtraSettingsUi.dp(this, 24), padding, ExtraSettingsUi.dp(this, 32));
		scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		setContentView(scroll);
		populateRoot();
	}

	private void populateRoot() {
		root.removeAllViews();
		root.addView(ExtraSettingsUi.title(this, R.string.steam_account_title));
		ExtraSettingsUi.addCardSpacing(root, buildStatusCard());
		ExtraSettingsUi.addCardSpacing(root, buildDownloadCard());
		ExtraSettingsUi.addCardSpacing(root, buildCloudCard());
		MaterialButton safetyNotice = ExtraSettingsUi.outlineButton(this, R.string.steam_account_safety_notice_open, R.drawable.ic_info_24);
		safetyNotice.setOnClickListener(v -> showSafetyNoticeDialog(false));
		ExtraSettingsUi.addCardSpacing(root, safetyNotice);
	}

	private View buildStatusCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_badge_24, R.string.steam_account_status_title, R.string.steam_account_status_subtitle, null));
		statusText = ExtraSettingsUi.body(this, "");
		ExtraSettingsUi.addSmallSpacing(content, statusText);
		progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		progressBar.setMax(100);
		progressBar.setVisibility(View.GONE);
		ExtraSettingsUi.addSmallSpacing(content, progressBar);
		progressText = ExtraSettingsUi.caption(this, "");
		ExtraSettingsUi.addSmallSpacing(content, progressText);

		LinearLayout row1 = ExtraSettingsUi.horizontal(this);
		MaterialButton login = ExtraSettingsUi.tonalButton(this, R.string.steam_login, R.drawable.ic_badge_24);
		MaterialButton verify = ExtraSettingsUi.outlineButton(this, R.string.steam_verify_login, R.drawable.ic_check_circle_24);
		login.setOnClickListener(v -> showLoginDialog());
		verify.setOnClickListener(v -> runOperation(getString(R.string.steam_status_verifying), () -> {
			String steamId = SteamLoginCoordinator.verifyRefreshToken(this);
			return getString(R.string.steam_status_verified, steamId);
		}));
		row1.addView(login, weighted(0));
		row1.addView(verify, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row1);

		MaterialButton logout = ExtraSettingsUi.outlineButton(this, R.string.steam_logout, R.drawable.ic_delete_24);
		logout.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_logout_confirm_title)
			.setMessage(R.string.steam_logout_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				SteamAuthStore.clear(this);
				refreshStatus();
				showMessage(getString(R.string.steam_logged_out));
			})
			.show());
		ExtraSettingsUi.addSmallSpacing(content, logout);
		return card;
	}

	private View buildDownloadCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_download_24, R.string.steam_payload_download_title, R.string.steam_payload_download_subtitle, null));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(this, getString(R.string.steam_payload_download_hint)));
		MaterialButton download = ExtraSettingsUi.tonalButton(this, R.string.steam_payload_download_button, R.drawable.ic_download_24);
		download.setOnClickListener(v -> showDownloadBranchDialog());
		ExtraSettingsUi.addSmallSpacing(content, download);
		return card;
	}

	private View buildCloudCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_save_24, R.string.steam_cloud_title, R.string.steam_cloud_subtitle, null));
		Sts2SteamCloudSyncManager.Status status = new Sts2SteamCloudSyncManager(this).getStatus();
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, getString(R.string.steam_cloud_profile_status, status.profileId, status.remoteFileCount, status.hasBaseline ? getString(R.string.yes) : getString(R.string.no))));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(this, status.accountRoot.getAbsolutePath()));

		LinearLayout modeRow = ExtraSettingsUi.horizontal(this);
		modeRow.addView(ExtraSettingsUi.body(this, R.string.steam_cloud_mode_title), weighted(0));
		Spinner modeSpinner = new Spinner(this);
		List<String> labels = Arrays.asList(
			getString(R.string.steam_cloud_mode_off),
			getString(R.string.steam_cloud_mode_manual),
			getString(R.string.steam_cloud_mode_pull_on_launch),
			getString(R.string.steam_cloud_mode_full_auto)
		);
		List<String> values = Arrays.asList(SteamSettings.CLOUD_MODE_OFF, SteamSettings.CLOUD_MODE_MANUAL, SteamSettings.CLOUD_MODE_PULL_ON_LAUNCH, SteamSettings.CLOUD_MODE_FULL_AUTO);
		ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		modeSpinner.setAdapter(adapter);
		modeSpinner.setSelection(Math.max(0, values.indexOf(SteamSettings.getCloudMode(this))), false);
		modeSpinner.post(() -> modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				SteamSettings.setCloudMode(SteamAccountActivity.this, values.get(position));
				refreshStatusOnly();
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {}
		}));
		modeRow.addView(modeSpinner, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, modeRow);

		MaterialSwitch settingsSwitch = new MaterialSwitch(this);
		settingsSwitch.setText(R.string.steam_cloud_sync_settings_save);
		settingsSwitch.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		settingsSwitch.setChecked(SteamSettings.shouldSyncSettingsSave(this));
		settingsSwitch.setOnCheckedChangeListener((button, checked) -> SteamSettings.setSyncSettingsSave(this, checked));
		ExtraSettingsUi.addSmallSpacing(content, settingsSwitch);

		LinearLayout row1 = ExtraSettingsUi.horizontal(this);
		MaterialButton refresh = ExtraSettingsUi.outlineButton(this, R.string.steam_cloud_refresh, R.drawable.ic_sync_24);
		MaterialButton pull = ExtraSettingsUi.tonalButton(this, R.string.steam_cloud_pull, R.drawable.ic_download_24);
		refresh.setOnClickListener(v -> runCloudOperation(operation -> operation.refreshManifest(this::setProgress)));
		pull.setOnClickListener(v -> confirmCloudOverwrite(false));
		row1.addView(refresh, weighted(0));
		row1.addView(pull, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row1);

		LinearLayout row2 = ExtraSettingsUi.horizontal(this);
		MaterialButton push = ExtraSettingsUi.outlineButton(this, R.string.steam_cloud_push, R.drawable.ic_upload_file_24);
		MaterialButton forcePush = ExtraSettingsUi.outlineButton(this, R.string.steam_cloud_force_push, R.drawable.ic_upload_file_24);
		push.setOnClickListener(v -> runCloudOperationWithConflictPrompt(operation -> operation.pushLocalChanges(false, this::setProgress)));
		forcePush.setOnClickListener(v -> confirmForcePush());
		row2.addView(push, weighted(0));
		row2.addView(forcePush, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row2);
		return card;
	}

	private void showLoginDialog() {
		LinearLayout content = ExtraSettingsUi.vertical(this);
		int padding = ExtraSettingsUi.dp(this, 8);
		content.setPadding(padding, padding, padding, 0);
		EditText username = new EditText(this);
		username.setHint(R.string.steam_username_hint);
		username.setSingleLine(true);
		EditText password = new EditText(this);
		password.setHint(R.string.steam_password_hint);
		password.setSingleLine(true);
		password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		content.addView(username);
		content.addView(password);
		SteamAuthStore.AuthSnapshot snapshot = SteamAuthStore.readSnapshot(this);
		if (!TextUtils.isEmpty(snapshot.accountName)) {
			username.setText(snapshot.accountName);
			username.setSelection(username.getText().length());
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_login)
			.setMessage(R.string.steam_login_message)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.steam_login, (dialog, which) -> {
				String user = username.getText() == null ? "" : username.getText().toString();
				String pass = password.getText() == null ? "" : password.getText().toString();
				runOperation(getString(R.string.steam_status_logging_in), () -> {
					SteamLoginCoordinator.AuthResult result = SteamLoginCoordinator.authenticateWithCredentials(this, user, pass, new DialogAuthPrompt());
					return getString(R.string.steam_login_done, result.accountName, result.steamId64);
				});
			})
			.show();
	}

	private void showDownloadBranchDialog() {
		EditText branch = new EditText(this);
		branch.setSingleLine(true);
		branch.setText(Sts2SteamPayloadDownloader.DEFAULT_BRANCH);
		branch.setSelection(branch.getText().length());
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_payload_download_button)
			.setMessage(R.string.steam_payload_branch_message)
			.setView(branch)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.steam_payload_download_button, (dialog, which) -> {
				String selectedBranch = branch.getText() == null ? "" : branch.getText().toString();
				runOperation(getString(R.string.steam_status_downloading_payload), () -> {
					PayloadManager.ImportControl control = new PayloadManager.ImportControl();
					PayloadManager.Status status = new Sts2SteamPayloadDownloader(this).downloadAndInstall(selectedBranch, progress -> {
						runOnUiThread(() -> setProgress(progress.getPercent(), progress.getMessage()));
						return kotlin.Unit.INSTANCE;
					}, control);
					try {
						CompatPackManager compat = new CompatPackManager(this);
						CompatPackManager.CompatPack match = compat.findBestMatch(status.manifest);
						if (match != null) {
							compat.selectPack(match.packId);
						}
					} catch (Exception ignored) {
					}
					return getString(R.string.status_import_game_payload_done, status.shortVersionLabel());
				});
			})
			.show();
	}

	private void confirmCloudOverwrite(boolean unused) {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_cloud_pull_confirm_title)
			.setMessage(R.string.steam_cloud_pull_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runCloudOperationWithConflictPrompt(operation -> operation.pullAll(this::setProgress)))
			.show();
	}

	private void confirmForcePush() {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_cloud_force_push_confirm_title)
			.setMessage(R.string.steam_cloud_force_push_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runCloudOperation(operation -> operation.pushLocalChanges(true, this::setProgress)))
			.show();
	}

	private void runCloudOperation(CloudOperation operation) {
		runOperation(getString(R.string.steam_status_cloud_busy), () -> operation.run(new Sts2SteamCloudSyncManager(this)));
	}

	private void runCloudOperationWithConflictPrompt(CloudOperation operation) {
		if (busy) {
			return;
		}
		String busyMessage = getString(R.string.steam_status_cloud_busy);
		busy = true;
		showOperationDialog(busyMessage);
		setProgress(0, busyMessage);
		new Thread(() -> {
			try {
				String result = operation.run(new Sts2SteamCloudSyncManager(this));
				runOnUiThread(() -> {
					busy = false;
					setProgress(100, result);
					dismissOperationDialog();
					refreshStatus();
					showMessage(result);
				});
			} catch (Exception exception) {
				Sts2SteamCloudSyncManager.CloudConflictException conflict = findCloudConflict(exception);
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
		}, "sts2-steam-cloud-operation").start();
	}

	private void showCloudConflictDialog(Sts2SteamCloudSyncManager.CloudConflictException conflict) {
		String message = getString(
			R.string.steam_cloud_conflict_message,
			conflict.getConflictCount(),
			conflict.getConflictSummary(8)
		);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_cloud_conflict_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.steam_cloud_conflict_keep_cloud, (dialog, which) -> runCloudOperation(operation -> operation.pullAll(true, this::setProgress)))
			.setPositiveButton(R.string.steam_cloud_conflict_keep_local, (dialog, which) -> runCloudOperation(operation -> operation.pushLocalChanges(true, this::setProgress)))
			.show();
	}

	private Sts2SteamCloudSyncManager.CloudConflictException findCloudConflict(Throwable exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof Sts2SteamCloudSyncManager.CloudConflictException) {
				return (Sts2SteamCloudSyncManager.CloudConflictException) current;
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
		}, "sts2-steam-operation").start();
	}

	private void showOperationDialog(String message) {
		dismissOperationDialog();
		operationDialog = new SteamOperationProgressDialog(this, getString(R.string.steam_operation_progress_title), message);
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
		refreshStatusOnly();
	}

	private void refreshStatusOnly() {
		if (statusText == null) {
			return;
		}
		SteamAuthStore.AuthSnapshot snapshot = SteamAuthStore.readSnapshot(this);
		String account = snapshot.refreshTokenConfigured ? snapshot.accountName : getString(R.string.steam_not_logged_in);
		String steamId = TextUtils.isEmpty(snapshot.steamId64) ? getString(R.string.unknown) : snapshot.steamId64;
		String mode = SteamSettings.getCloudMode(this);
		statusText.setText(getString(R.string.steam_account_status_format, account, steamId, mode, snapshot.lastError));
	}

	private void showMessage(String message) {
		Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
	}

	private LinearLayout.LayoutParams weighted(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(this, marginStartDp));
		return params;
	}

	private final class DialogAuthPrompt implements Sts2SteamCloudClient.AuthPrompt {
		@Override
		public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
			return requestTextCode(getString(previousCodeWasIncorrect ? R.string.steam_guard_device_code_retry : R.string.steam_guard_device_code), R.string.steam_guard_code_hint);
		}

		@Override
		public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
			String message = getString(previousCodeWasIncorrect ? R.string.steam_guard_email_code_retry : R.string.steam_guard_email_code, email == null ? "" : email);
			return requestTextCode(message, R.string.steam_guard_code_hint);
		}

		@Override
		public CompletableFuture<Boolean> acceptDeviceConfirmation() {
			CompletableFuture<Boolean> future = new CompletableFuture<>();
			runOnUiThread(() -> new MaterialAlertDialogBuilder(SteamAccountActivity.this)
				.setTitle(R.string.steam_guard_confirmation_title)
				.setMessage(R.string.steam_guard_confirmation_message)
				.setNegativeButton(android.R.string.cancel, (dialog, which) -> future.complete(false))
				.setPositiveButton(android.R.string.ok, (dialog, which) -> future.complete(true))
				.show());
			return future;
		}

		private CompletableFuture<String> requestTextCode(String message, int hintRes) {
			CompletableFuture<String> future = new CompletableFuture<>();
			runOnUiThread(() -> {
				EditText input = new EditText(SteamAccountActivity.this);
				input.setSingleLine(true);
				input.setHint(hintRes);
				input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
				new MaterialAlertDialogBuilder(SteamAccountActivity.this)
					.setTitle(R.string.steam_guard_title)
					.setMessage(message)
					.setView(input)
					.setNegativeButton(android.R.string.cancel, (dialog, which) -> future.completeExceptionally(new InterruptedException("Steam Guard cancelled.")))
					.setPositiveButton(android.R.string.ok, (dialog, which) -> future.complete(input.getText() == null ? "" : input.getText().toString().trim()))
					.show();
			});
			return future;
		}
	}

	private interface ThrowingSupplier { String run() throws Exception; }
	private interface CloudOperation { String run(Sts2SteamCloudSyncManager operation) throws Exception; }
}
