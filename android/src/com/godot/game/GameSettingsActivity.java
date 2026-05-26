package com.godot.game;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

public class GameSettingsActivity extends AppCompatActivity implements ExtraSettingsActions {
	private static final String TAG = "Sts2ExtraSettings";
	private static final int REQUEST_EXPORT_SAVE = 1001;
	private static final int REQUEST_IMPORT_SAVE = 1002;
	private static final int REQUEST_IMPORT_GAME_PAYLOAD = 1006;
	private static final int REQUEST_IMPORT_COMPAT_PACK = 1007;
	private static final int REQUEST_IMPORT_MOD = 1003;
	private static final int REQUEST_EXPORT_FULL_DATA_BACKUP = 1004;
	private static final int REQUEST_IMPORT_FULL_DATA_BACKUP = 1005;

	private ExtraSettingsRepository repository;
	private PayloadManager payloadManager;
	private CompatPackManager compatPackManager;
	private GameBodyVersionManager gameBodyVersionManager;
	private FrameLayout contentFrame;
	private BottomNavigationView bottomNavigationView;
	private boolean busy;
	private boolean launchUpdateCheckRequested;
	private boolean bundledPayloadAutoExtractRequested;
	private int currentTabId = R.id.nav_game;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		repository = new ExtraSettingsRepository(this);
		payloadManager = new PayloadManager(this);
		compatPackManager = new CompatPackManager(this);
		gameBodyVersionManager = new GameBodyVersionManager(this);
		repository.ensureAppDirectories();
		installBundledCompatPacksInBackground(false);
		if (!ExtraSettingsPreferences.isFirstRunSetupCompleted(this)) {
			showWelcome();
		} else {
			showMainShell();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (ExtraSettingsPreferences.isFirstRunSetupCompleted(this) && contentFrame != null) {
			refreshCurrentScreen();
		}
	}

	private void showWelcome() {
		WelcomeSetupPage page = new WelcomeSetupPage(this, repository, this, launchGame -> {
			if (launchGame) {
				launchGame();
			} else {
				showMainShell();
			}
		});
		setContentView(page.build());
	}

	private void showMainShell() {
		LinearLayout shell = new LinearLayout(this);
		shell.setOrientation(LinearLayout.VERTICAL);
		shell.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		contentFrame = new FrameLayout(this);
		shell.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		bottomNavigationView = new BottomNavigationView(this);
		bottomNavigationView.inflateMenu(R.menu.menu_extra_settings_nav);
		bottomNavigationView.setBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		ColorStateList itemColors = new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT }
		);
		bottomNavigationView.setItemIconTintList(itemColors);
		bottomNavigationView.setItemTextColor(itemColors);
		bottomNavigationView.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
		bottomNavigationView.setOnItemSelectedListener(item -> {
			openTab(item.getItemId());
			return true;
		});
		shell.addView(bottomNavigationView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		setContentView(shell);
		int savedTab = ExtraSettingsPreferences.getLastSelectedMainTab(this, R.id.nav_game);
		if (savedTab != R.id.nav_game && savedTab != R.id.nav_mods && savedTab != R.id.nav_versions && savedTab != R.id.nav_settings && savedTab != R.id.nav_about) {
			savedTab = R.id.nav_game;
		}
		bottomNavigationView.setSelectedItemId(savedTab);
		openTab(savedTab);
		maybeRunLaunchUpdateCheck();
		maybeAutoExtractBundledPayload();
	}

	private void maybeRunLaunchUpdateCheck() {
		if (launchUpdateCheckRequested || !ExtraSettingsPreferences.isUpdateCheckEnabled(this)) {
			return;
		}
		launchUpdateCheckRequested = true;
		requestUpdateCheck();
	}

	private void maybeAutoExtractBundledPayload() {
		if (bundledPayloadAutoExtractRequested || busy || !payloadManager.hasBundledPayload() || payloadManager.getStatus().ready) {
			return;
		}
		bundledPayloadAutoExtractRequested = true;
		requestExtractBundledPayload();
	}

	private void openTab(int itemId) {
		if (contentFrame == null) {
			return;
		}
		currentTabId = itemId;
		ExtraSettingsPreferences.setLastSelectedMainTab(this, itemId);
		View page;
		if (itemId == R.id.nav_mods) {
			page = new ModsPage(this, repository, this).build();
		} else if (itemId == R.id.nav_versions) {
			page = new GameVersionManagerPage(this, this).build();
		} else if (itemId == R.id.nav_settings) {
			page = new SettingsPage(this, repository, this).build();
		} else if (itemId == R.id.nav_about) {
			page = new AboutPage(this, this).build();
		} else {
			page = new GamePage(this, repository, this).build();
		}
		contentFrame.removeAllViews();
		contentFrame.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
	}

	@Override
	public void launchGame() {
		try {
			PayloadManager.Status payloadStatus = payloadManager.getStatus();
			if (!payloadStatus.ready) {
				if (payloadManager.hasBundledPayload()) {
					requestExtractBundledPayload();
				} else {
					showMessage(getString(R.string.launch_requires_payload));
				}
				return;
			}
			if (compatPackManager.isCompatPackEnabled()) {
				CompatPackManager.CompatPack compatPack = compatPackManager.findBestMatch(payloadStatus.manifest);
				if (compatPack != null && (compatPackManager.getSelectedPack() == null || !compatPack.packId.equals(compatPackManager.getSelectedPackId()))) {
					compatPackManager.selectPack(compatPack.packId);
				}
				CompatPackManager.CompatPack selectedCompatPack = compatPackManager.getSelectedPack();
				if (selectedCompatPack == null) {
					showMessage(getString(R.string.launch_requires_compat_pack));
					return;
				}
				if (!compatPackManager.isPackCompatibleWithPayload(selectedCompatPack, payloadStatus.manifest)) {
					showCompatMismatchDialog(payloadStatus, selectedCompatPack);
					return;
				}
			}
			prepareAndStartGame();
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	public void requestExportSave() {
		if (busy) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("application/zip");
		intent.putExtra(Intent.EXTRA_TITLE, repository.buildDefaultSaveExportName());
		startActivityForResult(intent, REQUEST_EXPORT_SAVE);
	}

	@Override
	public void requestImportSave() {
		if (busy) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/octet-stream" });
		startActivityForResult(intent, REQUEST_IMPORT_SAVE);
	}

	@Override
	public void requestImportGamePayload() {
		if (busy) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/octet-stream" });
		startActivityForResult(intent, REQUEST_IMPORT_GAME_PAYLOAD);
	}

	@Override
	public void requestExtractBundledPayload() {
		if (busy) {
			return;
		}
		runPayloadImportOperation(null, true);
	}

	@Override
	public void requestImportCompatPack() {
		if (busy) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/octet-stream" });
		startActivityForResult(intent, REQUEST_IMPORT_COMPAT_PACK);
	}

	@Override
	public void requestInstallBundledCompatPacks() {
		installBundledCompatPacksInBackground(true);
	}

	@Override
	public void requestSelectCompatPack(String packId) {
		runAsyncOperation(getString(R.string.status_busy_select_compat_pack), () -> {
			compatPackManager.selectPack(packId);
			return getString(R.string.status_select_compat_pack_done);
		});
	}

	@Override
	public void requestDeleteCompatPack(String packId) {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.delete_compat_pack_confirm_title)
			.setMessage(R.string.delete_compat_pack_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runAsyncOperation(getString(R.string.status_busy_delete_compat_pack), () -> {
				compatPackManager.deletePack(packId);
				return getString(R.string.status_delete_compat_pack_done);
			}))
			.show();
	}

	@Override
	public void requestClearTextureCache() {
		runAsyncOperation(getString(R.string.status_busy_prepare_launch), () -> {
			new GameLaunchPreparationManager(this).clearTextureCacheForNextLaunch();
			return getString(R.string.status_clear_texture_cache_done);
		});
	}

	@Override
	public void requestArchiveActiveGameVersion() {
		runAsyncOperation(getString(R.string.status_busy_archive_game_version), () -> {
			GameBodyVersionManager.GameBodyVersion version = gameBodyVersionManager.archiveActivePayload();
			return getString(R.string.status_archive_game_version_done, version.label);
		});
	}

	@Override
	public void requestSelectGameVersion(String versionId) {
		runAsyncOperation(getString(R.string.status_busy_select_game_version), () -> {
			gameBodyVersionManager.selectVersion(versionId);
			return getString(R.string.status_select_game_version_done);
		});
	}

	@Override
	public void requestDeleteGameVersion(String versionId) {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.delete_game_version_confirm_title)
			.setMessage(R.string.delete_game_version_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runAsyncOperation(getString(R.string.status_busy_delete_game_version), () -> {
				gameBodyVersionManager.deleteVersion(versionId);
				return getString(R.string.status_delete_game_version_done);
			}))
			.show();
	}

	@Override
	public void requestClearGamePayload() {
		if (busy) {
			return;
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.clear_game_payload_confirm_title)
			.setMessage(R.string.clear_game_payload_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runAsyncOperation(getString(R.string.status_busy_clear_game_payload), () -> {
				payloadManager.clearPayload();
				return getString(R.string.status_clear_game_payload_done);
			}))
			.show();
	}

	@Override
	public void requestExportFullDataBackup() {
		if (busy) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("application/zip");
		intent.putExtra(Intent.EXTRA_TITLE, repository.buildDefaultFullDataBackupName());
		startActivityForResult(intent, REQUEST_EXPORT_FULL_DATA_BACKUP);
	}

	@Override
	public void requestImportFullDataBackup() {
		if (busy) {
			return;
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.full_data_backup_import_confirm_title)
			.setMessage(R.string.full_data_backup_import_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
				intent.addCategory(Intent.CATEGORY_OPENABLE);
				intent.setType("*/*");
				intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/octet-stream" });
				startActivityForResult(intent, REQUEST_IMPORT_FULL_DATA_BACKUP);
			})
			.show();
	}

	@Override
	public void requestImportMod() {
		if (busy) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		startActivityForResult(intent, REQUEST_IMPORT_MOD);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode != RESULT_OK || data == null) {
			return;
		}
		if (requestCode == REQUEST_EXPORT_SAVE && data.getData() != null) {
			Uri uri = data.getData();
			runAsyncOperation(getString(R.string.status_busy_export_save), () -> {
				repository.exportSaveZip(uri);
				return getString(R.string.status_export_save_done);
			});
		} else if (requestCode == REQUEST_IMPORT_SAVE && data.getData() != null) {
			Uri uri = data.getData();
			runAsyncOperation(getString(R.string.status_busy_import_save), () -> {
				repository.importSaveZip(uri);
				return getString(R.string.status_import_save_done);
			});
		} else if (requestCode == REQUEST_IMPORT_GAME_PAYLOAD && data.getData() != null) {
			runPayloadImportOperation(data.getData(), false);
		} else if (requestCode == REQUEST_IMPORT_COMPAT_PACK && data.getData() != null) {
			Uri uri = data.getData();
			runAsyncOperation(getString(R.string.status_busy_import_compat_pack), () -> {
				CompatPackManager.CompatPack pack = compatPackManager.importCompatPack(uri);
				return getString(R.string.status_import_compat_pack_done, pack.displayName);
			});
		} else if (requestCode == REQUEST_EXPORT_FULL_DATA_BACKUP && data.getData() != null) {
			Uri uri = data.getData();
			runAsyncOperation(getString(R.string.status_busy_export_full_data_backup), () -> {
				repository.exportFullDataBackupZip(uri);
				return getString(R.string.status_export_full_data_backup_done);
			});
		} else if (requestCode == REQUEST_IMPORT_FULL_DATA_BACKUP && data.getData() != null) {
			Uri uri = data.getData();
			runAsyncOperation(getString(R.string.status_busy_import_full_data_backup), () -> {
				repository.importFullDataBackupZip(uri);
				return getString(R.string.status_import_full_data_backup_done);
			});
		} else if (requestCode == REQUEST_IMPORT_MOD) {
			List<Uri> uris = extractDocumentUris(data);
			if (uris.isEmpty()) {
				return;
			}
			runAsyncOperation(getString(R.string.status_busy_import_mod), () -> {
				int imported = 0;
				String lastName = "";
				for (Uri uri : uris) {
					lastName = repository.importMod(uri);
					imported++;
				}
				if (imported == 1 && lastName != null && !lastName.isEmpty()) {
					return getString(R.string.status_import_mod_done) + " " + lastName;
				}
				return getString(R.string.status_import_mod_done_count, imported);
			});
		}
	}

	private void runPayloadImportOperation(Uri uri, boolean bundled) {
		if (busy) {
			return;
		}
		busy = true;
		PayloadManager.ImportControl control = new PayloadManager.ImportControl();
		final Thread[] workerRef = new Thread[1];
		PayloadImportProgressDialog progressDialog = new PayloadImportProgressDialog(control, () -> {
			Thread worker = workerRef[0];
			if (worker != null) {
				worker.interrupt();
			}
		});
		progressDialog.show();

		Thread worker = new Thread(() -> {
			try {
				PayloadManager.Status status = bundled
					? payloadManager.extractBundledPayload((percent, stage) -> runOnUiThread(() -> progressDialog.setProgress(percent)), control)
					: payloadManager.importPayloadZip(uri, (percent, stage) -> runOnUiThread(() -> progressDialog.setProgress(percent)), control);
				try {
					gameBodyVersionManager.archiveActivePayload();
					CompatPackManager.CompatPack match = compatPackManager.findBestMatch(status.manifest);
					if (match != null) {
						compatPackManager.selectPack(match.packId);
					}
				} catch (Exception exception) {
					Log.w(TAG, "Unable to archive imported game or select matching compatibility pack.", exception);
				}
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					refreshCurrentScreen();
					showMessage(getString(R.string.status_import_game_payload_done, status.shortVersionLabel()));
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (control.isCancelled() || Thread.currentThread().isInterrupted() || isImportCancelledException(exception)) {
						showMessage(getString(R.string.status_import_game_payload_cancelled));
					} else {
						showError(exception);
					}
				});
			}
		}, "sts2-payload-import");
		workerRef[0] = worker;
		worker.start();
	}

	private void showCompatMismatchDialog(PayloadManager.Status payloadStatus, CompatPackManager.CompatPack selectedCompatPack) {
		String payloadVersion = compatPackManager.getPayloadVersion(payloadStatus.manifest);
		if (TextUtils.isEmpty(payloadVersion)) {
			payloadVersion = payloadStatus.shortVersionLabel();
		}
		String message = getString(R.string.launch_incompatible_compat_pack_dialog_message, selectedCompatPack.displayName, selectedCompatPack.targetLabel(), payloadVersion);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.launch_incompatible_compat_pack_dialog_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.tab_versions, (dialog, which) -> openVersionsTab())
			.setPositiveButton(R.string.launch_anyway, (dialog, which) -> prepareAndStartGame())
			.show();
	}

	private void prepareAndStartGame() {
		if (busy) {
			return;
		}
		busy = true;
		showMessage(getString(R.string.status_busy_prepare_launch));
		new Thread(() -> {
			try {
				new GameLaunchPreparationManager(this).prepareForLaunch();
				runOnUiThread(() -> {
					busy = false;
					Intent intent = GodotApp.createLaunchIntent(this, true);
					intent.putExtra("launch_prepared", true);
					startActivity(intent);
					finish();
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					showError(exception);
				});
			}
		}, "sts2-launch-prepare").start();
	}

	private void installBundledCompatPacksInBackground(boolean showResult) {
		if (showResult) {
			runAsyncOperation(getString(R.string.status_busy_install_bundled_compat), () -> {
				int count = compatPackManager.installBundledCompatPacks();
				return getString(R.string.status_install_bundled_compat_done, count);
			});
			return;
		}
		new Thread(() -> {
			try {
				compatPackManager.installBundledCompatPacks();
				runOnUiThread(this::refreshCurrentScreen);
			} catch (Exception exception) {
				Log.w(TAG, "Unable to install bundled compatibility packs.", exception);
			}
		}, "sts2-compat-bootstrap").start();
	}

	private boolean isImportCancelledException(Exception exception) {
		return exception != null && exception.getMessage() != null && exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("cancelled");
	}

	private final class PayloadImportProgressDialog {
		private final AlertDialog dialog;
		private final ObjectAnimator rotationAnimator;
		private final ProgressBar progressBar;
		private final MaterialButton cancelButton;

		PayloadImportProgressDialog(PayloadManager.ImportControl control, Runnable onCancel) {
			LinearLayout content = ExtraSettingsUi.vertical(GameSettingsActivity.this);
			content.setGravity(Gravity.CENTER_HORIZONTAL);
			int padding = ExtraSettingsUi.dp(GameSettingsActivity.this, 24);
			content.setPadding(padding, padding, padding, padding);

			ImageView gear = new ImageView(GameSettingsActivity.this);
			gear.setImageResource(R.drawable.ic_settings_24);
			gear.setColorFilter(ExtraSettingsUi.COLOR_PRIMARY);
			LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(GameSettingsActivity.this, 56), ExtraSettingsUi.dp(GameSettingsActivity.this, 56));
			gearParams.gravity = Gravity.CENTER_HORIZONTAL;
			content.addView(gear, gearParams);

			TextView title = ExtraSettingsUi.text(GameSettingsActivity.this, R.string.import_game_payload_dialog_title, 20, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD);
			title.setGravity(Gravity.CENTER);
			LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			titleParams.topMargin = ExtraSettingsUi.dp(GameSettingsActivity.this, 14);
			content.addView(title, titleParams);

			progressBar = new ProgressBar(GameSettingsActivity.this, null, android.R.attr.progressBarStyleHorizontal);
			progressBar.setMax(100);
			progressBar.setProgress(0);
			progressBar.setIndeterminate(false);
			LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(GameSettingsActivity.this, 10));
			progressParams.topMargin = ExtraSettingsUi.dp(GameSettingsActivity.this, 22);
			content.addView(progressBar, progressParams);

			cancelButton = ExtraSettingsUi.outlineButton(GameSettingsActivity.this, android.R.string.cancel, 0);
			LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			cancelParams.topMargin = ExtraSettingsUi.dp(GameSettingsActivity.this, 20);
			content.addView(cancelButton, cancelParams);

			dialog = new MaterialAlertDialogBuilder(GameSettingsActivity.this)
				.setView(content)
				.create();
			dialog.setCancelable(false);
			dialog.setCanceledOnTouchOutside(false);
			cancelButton.setOnClickListener(v -> {
				control.cancel();
				cancelButton.setEnabled(false);
				onCancel.run();
			});

			rotationAnimator = ObjectAnimator.ofFloat(gear, View.ROTATION, 0f, 360f);
			rotationAnimator.setDuration(1100L);
			rotationAnimator.setRepeatCount(ObjectAnimator.INFINITE);
			rotationAnimator.setInterpolator(new LinearInterpolator());
		}

		void show() {
			dialog.show();
			rotationAnimator.start();
		}

		void setProgress(int percent) {
			progressBar.setProgress(Math.max(0, Math.min(100, percent)));
		}

		void dismiss() {
			rotationAnimator.cancel();
			if (dialog.isShowing()) {
				dialog.dismiss();
			}
		}
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
		if (data.getData() != null && uris.isEmpty()) {
			uris.add(data.getData());
		}
		return uris;
	}

	@Override
	public void openLogViewer() {
		try {
			startActivity(new Intent(this, LogViewerActivity.class));
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	public void openFileBrowser() {
		try {
			startActivity(new Intent(this, FileBrowserActivity.class));
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	public void openUrl(String url) {
		try {
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			startActivity(intent);
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	public void requestUpdateCheck() {
		new Thread(() -> {
			try {
				ExtraSettingsUpdateChecker.UpdateInfo updateInfo = ExtraSettingsUpdateChecker.checkForUpdate(this);
				if (updateInfo != null) {
					runOnUiThread(() -> showUpdateDialog(updateInfo));
				}
			} catch (Exception exception) {
				Log.w(TAG, "Update check failed silently.", exception);
			}
		}, "sts2-update-check").start();
	}

	private void showUpdateDialog(ExtraSettingsUpdateChecker.UpdateInfo updateInfo) {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		String message = getString(
			R.string.update_available_message,
			BuildConfig.VERSION_NAME,
			updateInfo.versionName,
			updateInfo.changelog
		);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.update_available_title)
			.setMessage(message)
			.setNegativeButton(R.string.update_later, null)
			.setPositiveButton(R.string.update_download, (dialog, which) -> openUrl(updateInfo.releaseUrl))
			.show();
	}

	@Override
	public void openSettingsTab() {
		if (bottomNavigationView != null) {
			bottomNavigationView.setSelectedItemId(R.id.nav_settings);
		} else {
			openTab(R.id.nav_settings);
		}
	}

	@Override
	public void openVersionsTab() {
		if (bottomNavigationView != null) {
			bottomNavigationView.setSelectedItemId(R.id.nav_versions);
		} else {
			openTab(R.id.nav_versions);
		}
	}

	@Override
	public void refreshCurrentScreen() {
		if (contentFrame != null) {
			int scrollY = captureScrollY(contentFrame);
			openTab(currentTabId);
			restoreScrollY(scrollY);
		}
	}

	private int captureScrollY(View root) {
		ScrollView scrollView = findScrollView(root);
		return scrollView == null ? 0 : scrollView.getScrollY();
	}

	private void restoreScrollY(int scrollY) {
		if (scrollY <= 0 || contentFrame == null) {
			return;
		}
		contentFrame.post(() -> {
			ScrollView scrollView = findScrollView(contentFrame);
			if (scrollView == null) {
				return;
			}
			if (scrollView.getHeight() > 0 && scrollView.getChildCount() > 0 && scrollView.getChildAt(0).getHeight() > 0) {
				scrollView.post(() -> scrollToRestoredY(scrollView, scrollY));
				return;
			}
			ViewTreeObserver observer = scrollView.getViewTreeObserver();
			observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
				@Override
				public void onGlobalLayout() {
					ViewTreeObserver currentObserver = scrollView.getViewTreeObserver();
					if (currentObserver.isAlive()) {
						currentObserver.removeOnGlobalLayoutListener(this);
					}
					scrollView.post(() -> scrollToRestoredY(scrollView, scrollY));
				}
			});
		});
	}

	private void scrollToRestoredY(ScrollView scrollView, int scrollY) {
		int maxScrollY = scrollY;
		if (scrollView.getChildCount() > 0) {
			int viewportHeight = scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom();
			maxScrollY = Math.max(0, scrollView.getChildAt(0).getHeight() - viewportHeight);
		}
		scrollView.scrollTo(0, Math.min(scrollY, maxScrollY));
	}

	private ScrollView findScrollView(View view) {
		if (view instanceof ScrollView scrollView) {
			return scrollView;
		}
		if (view instanceof ViewGroup viewGroup) {
			for (int i = 0; i < viewGroup.getChildCount(); i++) {
				ScrollView found = findScrollView(viewGroup.getChildAt(i));
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	@Override
	public void showMessage(String message) {
		if (message == null || message.trim().isEmpty() || isSilentSettingsSavedMessage(message)) {
			return;
		}
		showSnackbar(message, Snackbar.LENGTH_SHORT);
	}

	@Override
	public void showError(Exception exception) {
		String message = getString(R.string.error_operation_failed) + ": " + (exception.getMessage() == null ? exception.toString() : exception.getMessage());
		showSnackbar(message, Snackbar.LENGTH_LONG);
		refreshCurrentScreen();
	}

	@Override
	public void runAsyncOperation(String busyMessage, ExtraSettingsRepository.ThrowingSupplier<String> operation) {
		if (busy) {
			return;
		}
		busy = true;
		if (!isSilentSettingsSavedMessage(busyMessage)) {
			showMessage(busyMessage);
		}
		new Thread(() -> {
			try {
				String result = operation.run();
				runOnUiThread(() -> {
					busy = false;
					if (!isFullDataRestoreMessage(result)) {
						refreshCurrentScreen();
					}
					if (!isSilentSettingsSavedMessage(result)) {
						showMessage(result);
					}
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					showError(exception);
				});
			}
		}).start();
	}

	private boolean isSilentSettingsSavedMessage(String message) {
		return message != null && message.equals(getString(R.string.status_settings_saved));
	}

	private boolean isFullDataRestoreMessage(String message) {
		return message != null && message.equals(getString(R.string.status_import_full_data_backup_done));
	}

	private void showSnackbar(String message, int duration) {
		View anchor = findViewById(android.R.id.content);
		if (anchor == null) {
			return;
		}
		Snackbar.make(anchor, message, duration).show();
	}

	@Override
	public void onBackPressed() {
		if (!ExtraSettingsPreferences.isFirstRunSetupCompleted(this)) {
			new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.welcome_exit_title)
				.setMessage(R.string.welcome_exit_message)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.welcome_enter_settings, (dialog, which) -> {
					ExtraSettingsPreferences.setFirstRunSetupCompleted(this, true);
					showMainShell();
				})
				.show();
			return;
		}
		super.onBackPressed();
	}
}
