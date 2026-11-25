package com.godot.game;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import com.godot.game.steam.auth.SteamAuthStore;
import com.godot.game.steam.cloud.SteamCleanExitTracker;
import com.godot.game.steam.cloud.Sts2SteamCloudSyncManager;
import com.godot.game.steam.core.SteamSettings;
import com.godot.game.webdav.WebDavSettings;
import com.godot.game.webdav.WebDavSyncManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameSettingsActivity extends AppCompatActivity implements ExtraSettingsActions {
	private static final String TAG = "Sts2ExtraSettings";
	private static final String UPDATE_BILIBILI_DYNAMIC_URL = "https://space.bilibili.com/116375500/dynamic";
	private static final int REQUEST_EXPORT_SAVE = 1001;
	private static final int REQUEST_IMPORT_SAVE = 1002;
	private static final int REQUEST_IMPORT_GAME_PAYLOAD = 1006;
	private static final int REQUEST_IMPORT_COMPAT_PACK = 1007;
	private static final int REQUEST_IMPORT_MOD = 1003;
	private static final int REQUEST_EXPORT_FULL_DATA_BACKUP = 1004;
	private static final int REQUEST_IMPORT_FULL_DATA_BACKUP = 1005;
	private static final int REQUEST_STEAM_PAYLOAD_RESULT = 1008;
	private static final long COMPAT_LAUNCH_DIALOG_CHECK_DEBOUNCE_MS = 45_000L;
	private static final ExecutorService MOD_IMPORT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "sts2-mod-import"));

	private ExtraSettingsRepository repository;
	private PayloadManager payloadManager;
	private CompatPackManager compatPackManager;
	private CompatPackReleaseRepository compatReleaseRepository;
	private CompatPackReleaseIndex compatReleaseIndex;
	private long compatReleaseLastChecked;
	private long lastCompatLaunchDialogCheckElapsedMs = -1L;
	private int compatReleaseCheckMessage;
	private boolean startupCompatCheckRequested;
	private boolean compatImportCheckPending;
	private boolean compatReleaseChecking;
	private boolean compatReleaseForceInFlight;
	private boolean compatReleaseForceQueued;
	private Dialog compatReleaseDialog;
	private Runnable renderCompatReleaseDialog;
	private AlertDialog compatDownloadDialog;
	private CancellationSignal compatDownloadCancellation;
	private long compatNavigationGeneration;
	private long compatForegroundGeneration;
	private GameBodyVersionManager gameBodyVersionManager;
	private LaunchProfileManager launchProfileManager;
	private FrameLayout contentFrame;
	private GamePage homePage;
	private boolean resumed;
	private BottomNavigationView bottomNavigationView;
	private NavigationRailView navigationRailView;
	private boolean busy;
	private boolean launchUpdateCheckRequested;
	private boolean bundledPayloadAutoExtractRequested;
	private boolean bundledPayloadAutoExtractCheckRunning;
	private String pendingBundledPayloadProfile;
	private boolean bundledPayloadImportStarted;
	private boolean bundledCompatPackBootstrapFinished;
	private boolean pendingLauncherDirectLaunch;
	private boolean pendingCompatRecommendationLaunch;
	private boolean preLaunchLocalSnapshotCreated;
	private boolean cleanExitMaintenanceChecked;
	private int currentTabId = R.id.nav_game;
	private volatile boolean importDestroyed;
	private List<ExtraSettingsRepository.PreparedModImport> activeModImports = new ArrayList<>();
	private final List<String> modImportNotices = new ArrayList<>();
	private AlertDialog modImportDialog;
	private boolean directoriesReady;
	private int pendingPickerRequest;
	private Intent pendingPickerResult;
	private Runnable cancelLaunchPreparation;
	private final Handler startupHandler = new Handler(Looper.getMainLooper());
	private final Runnable continueStartup = this::continueStartupWhenIdle;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		repository = new ExtraSettingsRepository(this);
		payloadManager = new PayloadManager(this);
		compatPackManager = new CompatPackManager(this);
		compatReleaseRepository = new CompatPackReleaseRepository(getApplicationContext());
		gameBodyVersionManager = new GameBodyVersionManager(this);
		launchProfileManager = new LaunchProfileManager(this);
		if (savedInstanceState != null) {
			pendingPickerRequest = savedInstanceState.getInt("pending_picker_request");
			pendingPickerResult = savedInstanceState.getParcelable("pending_picker_result");
		}
		// Resolving the selected roots also bootstraps launch profiles, on the readiness worker.
		ModInstallReadiness.open(this, repository::ensureAppDirectories, () -> openSettings(savedInstanceState));
	}

	private void openSettings(Bundle savedInstanceState) {
		directoriesReady = true;
		SteamWorkshopDownloadCleaner.maybeRunDailyCleanup(this);
		installBundledCompatPacksInBackground(false);
		if (!ExtraSettingsPreferences.isFirstRunSetupCompleted(this)) {
			showWelcome();
			deliverPendingPickerResult();
		} else {
			showMainShell();
			maybeLaunchGameFromLauncherIntent(savedInstanceState);
		}
	}

	private void deliverPendingPickerResult() {
		if (pendingPickerResult == null) return;
		Intent data = pendingPickerResult;
		pendingPickerResult = null;
		handlePickerResult(pendingPickerRequest, data);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (pendingPickerResult != null) {
			outState.putInt("pending_picker_request", pendingPickerRequest);
			outState.putParcelable("pending_picker_result", pendingPickerResult);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		resumed = true;
		if (directoriesReady && contentFrame != null) {
			if (currentTabId == R.id.nav_game && homePage != null) {
				homePage.setPageActive(true);
			} else {
				refreshCurrentScreen();
			}
			maybeRunCleanExitSteamCloudPush();
		}
		updateCompatReleaseDialog();
		scheduleStartupContinuation();
	}

	@Override
	protected void onPause() {
		resumed = false;
		compatForegroundGeneration++;
		startupHandler.removeCallbacks(continueStartup);
		if (homePage != null) homePage.setPageActive(false);
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		importDestroyed = true;
		startupHandler.removeCallbacks(continueStartup);
		if (compatDownloadCancellation != null) compatDownloadCancellation.cancel();
		if (compatDownloadDialog != null) compatDownloadDialog.dismiss();
		if (compatReleaseDialog != null) compatReleaseDialog.dismiss();
		if (homePage != null) homePage.setPageActive(false);
		if (modImportDialog != null) modImportDialog.dismiss();
		discardModImports(activeModImports);
		super.onDestroy();
	}

	private boolean isModImportUiUnavailable() {
		return importDestroyed || isFinishing() || isDestroyed();
	}

	private void discardModImports(List<ExtraSettingsRepository.PreparedModImport> imports) {
		MOD_IMPORT_EXECUTOR.execute(() -> {
			for (ExtraSettingsRepository.PreparedModImport prepared : imports) repository.discardPreparedModImport(prepared);
		});
	}

	private void showModImportNotices() {
		if (!modImportNotices.isEmpty() && !isModImportUiUnavailable()) {
			modImportDialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.mod_import_file_notice_title)
				.setMessage(TextUtils.join("\n\n", modImportNotices))
				.setPositiveButton(android.R.string.ok, null).show();
		}
		modImportNotices.clear();
	}

	private void showWelcome() {
		WelcomeSetupPage page = new WelcomeSetupPage(this, repository, this, launchGame -> {
			maybeCheckCompatOnStartup();
			if (launchGame) {
				launchGame();
			} else {
				showMainShell();
			}
		});
		setContentView(page.build());
	}

	private void showMainShell() {
		showMainShell(ExtraSettingsPreferences.getLastSelectedMainTab(this, R.id.nav_game));
	}

	private void showMainShell(int initialTab) {
		boolean wideLayout = ExtraSettingsUi.isWideLayout(this);
		LinearLayout shell = new LinearLayout(this);
		shell.setOrientation(wideLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
		shell.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		contentFrame = new FrameLayout(this);
		SystemBarInsetsHelper.applySystemBarPadding(contentFrame, false, true, false, true);
		bottomNavigationView = null;
		navigationRailView = null;
		if (wideLayout) {
			navigationRailView = new NavigationRailView(this);
			navigationRailView.inflateMenu(R.menu.menu_extra_settings_nav);
			configureNavigationBar(navigationRailView, ExtraSettingsUi.dp(this, 72));
			navigationRailView.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
			navigationRailView.setItemMinimumHeight(ExtraSettingsUi.dp(this, 64));
			navigationRailView.setPadding(0, ExtraSettingsUi.dp(this, 12), 0, ExtraSettingsUi.dp(this, 12));
			SystemBarInsetsHelper.applySystemBarPadding(navigationRailView, true, false, true, false);
			shell.addView(navigationRailView, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 96), ViewGroup.LayoutParams.MATCH_PARENT));
			shell.addView(contentFrame, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
		} else {
			shell.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
			bottomNavigationView = new BottomNavigationView(this);
			bottomNavigationView.inflateMenu(R.menu.menu_extra_settings_nav);
			configureNavigationBar(bottomNavigationView, ExtraSettingsUi.dp(this, 64));
			bottomNavigationView.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
			SystemBarInsetsHelper.applySystemBarPadding(bottomNavigationView, false, false, true, false);
			shell.addView(bottomNavigationView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}

		setContentView(shell);
		if (initialTab != R.id.nav_game && initialTab != R.id.nav_mods && initialTab != R.id.nav_versions && initialTab != R.id.nav_settings && initialTab != R.id.nav_about) {
			initialTab = R.id.nav_game;
		}
		selectNavigationItem(initialTab);
		// A returning picker gets the busy slot before automatic startup operations.
		deliverPendingPickerResult();
		maybeRunLaunchUpdateCheck();
		maybeAutoExtractBundledPayload();
		maybeRunCleanExitSteamCloudPush();
	}

	private void configureNavigationBar(NavigationBarView navigationBar, int activeIndicatorWidth) {
		navigationBar.setBackgroundColor(Color.rgb(30, 35, 31));
		ColorStateList itemIconColors = new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { Color.rgb(207, 233, 214), Color.rgb(193, 201, 193) }
		);
		ColorStateList itemTextColors = new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { Color.rgb(225, 227, 223), Color.rgb(193, 201, 193) }
		);
		navigationBar.getMenu().findItem(R.id.nav_game).setIcon(MaterialSymbols.drawable(this, "stadia_controller", itemIconColors, 24));
		navigationBar.getMenu().findItem(R.id.nav_mods).setIcon(MaterialSymbols.drawable(this, "extension", itemIconColors, 24));
		navigationBar.getMenu().findItem(R.id.nav_versions).setIcon(MaterialSymbols.drawable(this, "layers", itemIconColors, 24));
		navigationBar.getMenu().findItem(R.id.nav_settings).setIcon(MaterialSymbols.drawable(this, "settings", itemIconColors, 24));
		navigationBar.getMenu().findItem(R.id.nav_about).setIcon(MaterialSymbols.drawable(this, "info", itemIconColors, 24));
		navigationBar.setItemIconTintList(itemIconColors);
		navigationBar.setItemTextColor(itemTextColors);
		navigationBar.setItemRippleColor(ColorStateList.valueOf(Color.argb(72, 129, 217, 154)));
		navigationBar.setItemActiveIndicatorEnabled(true);
		navigationBar.setItemActiveIndicatorColor(ColorStateList.valueOf(Color.rgb(51, 75, 59)));
		navigationBar.setItemActiveIndicatorWidth(activeIndicatorWidth);
		navigationBar.setItemActiveIndicatorHeight(ExtraSettingsUi.dp(this, 32));
		navigationBar.setItemActiveIndicatorShapeAppearance(
			com.google.android.material.shape.ShapeAppearanceModel.builder()
				.setAllCornerSizes(ExtraSettingsUi.dp(this, 16))
				.build()
		);
		navigationBar.setItemTextAppearanceActiveBoldEnabled(true);
		navigationBar.setOnItemSelectedListener(item -> {
			openTab(item.getItemId());
			return true;
		});
	}

	private void selectNavigationItem(int itemId) {
		NavigationBarView bar = navigationRailView != null ? navigationRailView : bottomNavigationView;
		if (bar == null) return;
		if (bar.getSelectedItemId() == itemId) {
			// Includes the menu's initially checked item, before it has a page.
			openTab(itemId);
		} else {
			bar.setSelectedItemId(itemId); // The selection listener builds the page.
		}
	}

	private void maybeRunLaunchUpdateCheck() {
		if (launchUpdateCheckRequested || !ExtraSettingsPreferences.isUpdateCheckEnabled(this)) {
			return;
		}
		launchUpdateCheckRequested = true;
		requestUpdateCheck();
	}

	private void maybeAutoExtractBundledPayload() {
		if (bundledPayloadAutoExtractRequested) {
			return;
		}
		bundledPayloadAutoExtractRequested = true;
		bundledPayloadAutoExtractCheckRunning = true;
		String profileId = launchProfileManager.getSelectedProfileId();
		new Thread(() -> {
			try {
				boolean needsImport = payloadManager.hasBundledPayload()
					&& (!payloadManager.getStatus().ready || !payloadManager.isCurrentBundledPayloadImported());
				runOnUiThreadIfActive(() -> {
					bundledPayloadAutoExtractCheckRunning = false;
					pendingBundledPayloadProfile = needsImport && !bundledPayloadImportStarted ? profileId : null;
					scheduleStartupContinuation();
				});
			} catch (Exception exception) {
				runOnUiThreadIfActive(() -> {
					bundledPayloadAutoExtractCheckRunning = false;
					scheduleStartupContinuation();
				});
				Log.w(TAG, "Unable to check bundled payload import state.", exception);
			}
		}, "sts2-bundled-payload-check").start();
	}

	private void maybeLaunchGameFromLauncherIntent(Bundle savedInstanceState) {
		if (savedInstanceState != null || contentFrame == null) {
			return;
		}
		if (!ExtraSettingsPreferences.LAUNCHER_STARTUP_GAME.equals(ExtraSettingsPreferences.getLauncherStartupBehavior(this))) {
			return;
		}
		Intent intent = getIntent();
		if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction()) || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
			return;
		}
		pendingLauncherDirectLaunch = true;
		scheduleStartupContinuation();
	}

	private void scheduleStartupContinuation() {
		startupHandler.removeCallbacks(continueStartup);
		if (resumed && (pendingBundledPayloadProfile != null || pendingLauncherDirectLaunch || pendingCompatRecommendationLaunch)) {
			startupHandler.post(continueStartup);
		}
	}

	private void continueStartupWhenIdle() {
		if (!resumed || isFinishing() || isDestroyed() || bundledPayloadAutoExtractCheckRunning) return;
		if (pendingBundledPayloadProfile != null && !pendingBundledPayloadProfile.equals(launchProfileManager.getSelectedProfileId())) {
			pendingBundledPayloadProfile = null;
		}
		boolean launchReady = bundledCompatPackBootstrapFinished && (pendingLauncherDirectLaunch || pendingCompatRecommendationLaunch);
		if (pendingBundledPayloadProfile == null && !launchReady) return;
		if (busy) {
			startupHandler.postDelayed(continueStartup, 500);
		} else if (pendingBundledPayloadProfile != null) {
			requestExtractBundledPayload();
			scheduleStartupContinuation();
		} else {
			launchGame();
		}
	}

	private void openTab(int itemId) {
		if (contentFrame == null) {
			return;
		}
		if (homePage != null && itemId != R.id.nav_game) homePage.setPageActive(false);
		if (currentTabId != itemId) compatNavigationGeneration++;
		currentTabId = itemId;
		ExtraSettingsPreferences.setLastSelectedMainTab(this, itemId);
		View page;
		if (itemId == R.id.nav_mods) {
			page = new ModsPage(this, repository, this).build();
		} else if (itemId == R.id.nav_versions) {
			page = new GameVersionManagerPage(this, this, compatReleaseRepository, () -> compatReleaseIndex).build();
		} else if (itemId == R.id.nav_settings) {
			page = new SettingsPage(this, repository, this).build();
		} else if (itemId == R.id.nav_about) {
			page = new AboutPage(this, this).build();
		} else {
			if (homePage == null) homePage = new GamePage(this, repository, this);
			page = homePage.build();
			if (page.getParent() == contentFrame) return;
		}
		contentFrame.removeAllViews();
		contentFrame.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		if (itemId == R.id.nav_game) homePage.setPageActive(resumed);
	}

	@Override
	public void launchGame() {
		if (busy) return;
		// A manual click is also a launch request; do not replay a queued automatic click.
		pendingLauncherDirectLaunch = false;
		pendingCompatRecommendationLaunch = false;
		preLaunchLocalSnapshotCreated = false;
		try {
			LaunchProfileManager.LaunchProfile selectedProfile = launchProfileManager.getSelectedProfile();
			if (selectedProfile != null && !selectedProfile.ready) {
				showLaunchProfilePayloadMissingDialog(selectedProfile);
				return;
			}
			PayloadManager.Status payloadStatus = payloadManager.getStatus();
			if (!payloadStatus.ready) {
				if (payloadManager.hasBundledPayload()) {
					requestExtractBundledPayload();
				} else {
					showMessage(getString(R.string.launch_requires_payload));
				}
				return;
			}
			if (!compatPackManager.isCompatPackEnabled()) {
				showCompatDisabledLaunchWarning();
				return;
			}
			launchProfileManager.refreshAutomaticOfflineCompatSelection();
			CompatPackManager.CompatPack selectedCompatPack = compatPackManager.getSelectedPack();
			if (selectedCompatPack == null || !selectedCompatPack.ready) {
				if (!bundledCompatPackBootstrapFinished) {
					pendingCompatRecommendationLaunch = true;
					showMessage(getString(R.string.status_checking_compat_recommendation));
					return;
				}
				showMissingCompatPackSheet(payloadStatus, selectedProfile);
				return;
			}
			if (!compatPackManager.isPackCompatibleWithPayload(selectedCompatPack, payloadStatus.manifest)) {
				showCompatMismatchDialog(payloadStatus, selectedCompatPack);
				return;
			}
			if (compatPackManager.requiresOfflineBootstrapApproval(selectedCompatPack, payloadStatus.manifest)) {
				showOfflineBootstrapLaunchWarning(payloadStatus, selectedCompatPack, selectedProfile);
				return;
			}
			prepareAndStartGameAfterOptionalSteamCloudPull();
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
		startActivityForResult(intent, REQUEST_IMPORT_GAME_PAYLOAD);
	}

	@Override
	public void requestExtractBundledPayload() {
		if (busy) {
			return;
		}
		bundledPayloadImportStarted = true;
		pendingBundledPayloadProfile = null;
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
		requestDeleteGamePayload(versionId);
	}

	@Override
	public void requestCreateLaunchProfile(String payloadId) {
		showLaunchProfileDialog(null, payloadId);
	}

	@Override
	public void requestEditLaunchProfile(String profileId) {
		showLaunchProfileDialog(profileId, null);
	}

	@Override
	public void requestSelectLaunchProfile(String profileId) {
		runAsyncOperation(getString(R.string.status_busy_select_launch_profile), () -> {
			launchProfileManager.selectProfile(profileId);
			return getString(R.string.status_select_launch_profile_done);
		});
	}

	@Override
	public void requestDeleteLaunchProfile(String profileId) {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.delete_launch_profile_confirm_title)
			.setMessage(R.string.delete_launch_profile_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runAsyncOperation(getString(R.string.status_busy_delete_launch_profile), () -> {
				launchProfileManager.deleteProfile(profileId);
				repository.ensureAppDirectories();
				return getString(R.string.status_delete_launch_profile_done);
			}))
			.show();
	}

	@Override
	public void requestDeleteGamePayload(String payloadId) {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.delete_game_payload_confirm_title)
			.setMessage(R.string.delete_game_payload_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runAsyncOperation(getString(R.string.status_busy_delete_game_version), () -> {
				launchProfileManager.deletePayload(payloadId);
				repository.ensureAppDirectories();
				return getString(R.string.status_delete_game_version_done);
			}))
			.show();
	}

	private void showLaunchProfileDialog(String profileId, String payloadId) {
		try {
			LaunchProfileManager.LaunchProfile profile = TextUtils.isEmpty(profileId) ? null : launchProfileManager.readProfile(profileId);
			final LaunchProfileManager.GamePayload[] selectedPayload = new LaunchProfileManager.GamePayload[] { profile != null ? profile.payload : launchProfileManager.readPayload(payloadId) };
			if (profile == null && (selectedPayload[0] == null || !selectedPayload[0].ready)) {
				showMessage(getString(R.string.launch_profile_payload_missing));
				return;
			}
			CompatPackManager.CompatPack initialCompatPack = profile == null ? findBestCompatPackForPayload(selectedPayload[0]) : findCompatPackById(compatPackManager.listInstalledPacks(), profile.compatPackId, profile.compatTargetId);
			final String[] selectedCompatPackId = new String[] { initialCompatPack == null ? (profile == null ? "" : profile.compatPackId) : initialCompatPack.packId };
			final String[] selectedCompatTargetId = new String[] { initialCompatPack == null ? (profile == null ? "" : profile.compatTargetId) : initialCompatPack.targetId };
			final String[] selectedCompatSource = new String[] { profile == null ? LaunchProfileManager.COMPAT_SELECTION_AUTO : null };
			final MaterialButton[] compatButtonRef = new MaterialButton[1];

			BottomSheetDialog dialog = new BottomSheetDialog(this);
			LinearLayout content = ExtraSettingsUi.vertical(this);
			content.setPadding(ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 32));
			content.addView(ExtraSettingsUi.text(this, profile == null ? R.string.create_launch_profile_title : R.string.edit_launch_profile_title, 22, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));

			EditText nameInput = new EditText(this);
			nameInput.setSingleLine(true);
			nameInput.setHint(R.string.launch_profile_name_hint);
			nameInput.setText(profile == null ? selectedPayload[0].label : profile.displayName);
			nameInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			nameInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
			ExtraSettingsUi.addSmallSpacing(content, nameInput);

			MaterialButton selectPayload = ExtraSettingsUi.outlineButton(this, R.string.launch_profile_select_payload_title, R.drawable.ic_folder_24);
			selectPayload.setSingleLine(false);
			selectPayload.setEllipsize(null);
			selectPayload.setText(getString(R.string.launch_profile_select_payload_button, launchProfilePayloadLabel(profile, selectedPayload[0])));
			selectPayload.setOnClickListener(v -> showLaunchProfilePayloadPicker(selectedPayload[0], picked -> {
				selectedPayload[0] = picked;
				CompatPackManager.CompatPack pickedCompat = findBestCompatPackForPayload(picked);
				selectedCompatPackId[0] = pickedCompat == null ? "" : pickedCompat.packId;
				selectedCompatTargetId[0] = pickedCompat == null ? "" : pickedCompat.targetId;
				selectedCompatSource[0] = LaunchProfileManager.COMPAT_SELECTION_AUTO;
				selectPayload.setText(getString(R.string.launch_profile_select_payload_button, picked.label));
				if (compatButtonRef[0] != null) {
					setLaunchProfileCompatButtonText(compatButtonRef[0], selectedCompatPackId[0], selectedCompatTargetId[0]);
				}
				String currentName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
				if (currentName.isEmpty()) {
					nameInput.setText(picked.label);
				}
			}));
			ExtraSettingsUi.addSmallSpacing(content, selectPayload);

			MaterialButton selectCompat = ExtraSettingsUi.outlineButton(this, R.string.launch_profile_select_compat_title, R.drawable.ic_extension_24);
			selectCompat.setSingleLine(false);
			selectCompat.setEllipsize(null);
			compatButtonRef[0] = selectCompat;
			setLaunchProfileCompatButtonText(selectCompat, selectedCompatPackId[0], selectedCompatTargetId[0]);
			selectCompat.setOnClickListener(v -> showLaunchProfileCompatPicker(selectedCompatPackId[0], selectedCompatTargetId[0], selectedPayload[0], picked -> {
				selectedCompatPackId[0] = picked.packId;
				selectedCompatTargetId[0] = picked.targetId;
				selectedCompatSource[0] = LaunchProfileManager.COMPAT_SELECTION_MANUAL;
				setLaunchProfileCompatButtonText(selectCompat, selectedCompatPackId[0], selectedCompatTargetId[0]);
			}));
			ExtraSettingsUi.addSmallSpacing(content, selectCompat);

			TextView saveLabel = ExtraSettingsUi.sectionTitle(this, getString(R.string.launch_profile_save_mode_title));
			ExtraSettingsUi.addSmallSpacing(content, saveLabel);
			RadioGroup saveGroup = new RadioGroup(this);
			saveGroup.setOrientation(RadioGroup.VERTICAL);
			RadioButton saveGlobal = new RadioButton(this);
			saveGlobal.setText(R.string.launch_profile_save_mode_global);
			saveGlobal.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			saveGlobal.setButtonTintList(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY));
			saveGlobal.setId(View.generateViewId());
			RadioButton saveIsolated = new RadioButton(this);
			saveIsolated.setText(R.string.launch_profile_save_mode_isolated);
			saveIsolated.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			saveIsolated.setButtonTintList(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY));
			saveIsolated.setId(View.generateViewId());
			saveGroup.addView(saveGlobal);
			saveGroup.addView(saveIsolated);
			saveGroup.check((profile == null || LaunchProfileManager.SAVE_MODE_ISOLATED.equals(profile.saveMode)) ? saveIsolated.getId() : saveGlobal.getId());
			content.addView(saveGroup);

			TextView modsLabel = ExtraSettingsUi.sectionTitle(this, getString(R.string.launch_profile_mods_mode_title));
			ExtraSettingsUi.addSmallSpacing(content, modsLabel);
			RadioGroup modsGroup = new RadioGroup(this);
			modsGroup.setOrientation(RadioGroup.VERTICAL);
			RadioButton modsGlobal = new RadioButton(this);
			modsGlobal.setText(R.string.launch_profile_mods_mode_global);
			modsGlobal.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			modsGlobal.setButtonTintList(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY));
			modsGlobal.setId(View.generateViewId());
			RadioButton modsIsolated = new RadioButton(this);
			modsIsolated.setText(R.string.launch_profile_mods_mode_isolated);
			modsIsolated.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			modsIsolated.setButtonTintList(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY));
			modsIsolated.setId(View.generateViewId());
			modsGroup.addView(modsGlobal);
			modsGroup.addView(modsIsolated);
			modsGroup.check((profile == null || LaunchProfileManager.MODS_MODE_ISOLATED.equals(profile.modsMode)) ? modsIsolated.getId() : modsGlobal.getId());
			content.addView(modsGroup);

			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(this, getString(R.string.launch_profile_mode_hint)));

			LinearLayout buttons = ExtraSettingsUi.horizontal(this);
			buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
			MaterialButton cancel = ExtraSettingsUi.outlineButton(this, android.R.string.cancel, 0);
			MaterialButton ok = ExtraSettingsUi.tonalButton(this, android.R.string.ok, 0);
			cancel.setOnClickListener(v -> dialog.dismiss());
			ok.setOnClickListener(v -> {
				String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
				String saveMode = saveGroup.getCheckedRadioButtonId() == saveIsolated.getId() ? LaunchProfileManager.SAVE_MODE_ISOLATED : LaunchProfileManager.SAVE_MODE_GLOBAL;
				String modsMode = modsGroup.getCheckedRadioButtonId() == modsIsolated.getId() ? LaunchProfileManager.MODS_MODE_ISOLATED : LaunchProfileManager.MODS_MODE_GLOBAL;
				dialog.dismiss();
				runAsyncOperation(getString(R.string.status_busy_save_launch_profile), () -> {
					if (profile == null) {
						if (selectedPayload[0] == null || !selectedPayload[0].ready) {
							throw new IllegalStateException(getString(R.string.launch_profile_payload_missing));
						}
						launchProfileManager.createProfile(selectedPayload[0].id, name, saveMode, modsMode, selectedCompatPackId[0], selectedCompatTargetId[0], selectedCompatSource[0], true);
						repository.ensureAppDirectories();
						return getString(R.string.status_create_launch_profile_done);
					}
					String updatedPayloadId = selectedPayload[0] == null ? profile.payloadId : selectedPayload[0].id;
					launchProfileManager.updateProfile(profile.id, updatedPayloadId, name, saveMode, modsMode, selectedCompatPackId[0], selectedCompatTargetId[0], selectedCompatSource[0]);
					repository.ensureAppDirectories();
					return getString(R.string.status_update_launch_profile_done);
				});
			});
			buttons.addView(cancel);
			LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			okParams.setMarginStart(ExtraSettingsUi.dp(this, 10));
			buttons.addView(ok, okParams);
			ExtraSettingsUi.addSmallSpacing(content, buttons);

			ScrollableBottomSheet.setContent(dialog, content);
			dialog.show();
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private String launchProfilePayloadLabel(LaunchProfileManager.LaunchProfile profile, LaunchProfileManager.GamePayload payload) {
		if (payload != null) {
			return payload.label;
		}
		if (profile != null && !TextUtils.isEmpty(profile.payloadId)) {
			return getString(R.string.launch_profile_payload_missing_format, profile.payloadId);
		}
		return getString(R.string.launch_profile_payload_missing);
	}

	private String findBestCompatPackIdForPayload(LaunchProfileManager.GamePayload payload) {
		CompatPackManager.CompatPack pack = findBestCompatPackForPayload(payload);
		return pack == null ? "" : pack.packId;
	}

	private CompatPackManager.CompatPack findBestCompatPackForPayload(LaunchProfileManager.GamePayload payload) {
		try {
			return payload == null ? null : compatPackManager.findBestMatch(payload.manifest);
		} catch (Exception ignored) {
			return null;
		}
	}

	private void setLaunchProfileCompatButtonText(MaterialButton button, String compatPackId) {
		setLaunchProfileCompatButtonText(button, compatPackId, "");
	}

	private void setLaunchProfileCompatButtonText(MaterialButton button, String compatPackId, String compatTargetId) {
		button.setText(getString(R.string.launch_profile_select_compat_button, launchProfileCompatLabel(compatPackId, compatTargetId)));
	}

	private String launchProfileCompatLabel(String compatPackId) {
		return launchProfileCompatLabel(compatPackId, "");
	}

	private String launchProfileCompatLabel(String compatPackId, String compatTargetId) {
		if (TextUtils.isEmpty(compatPackId)) {
			return getString(R.string.launch_profile_select_compat_none);
		}
		CompatPackManager.CompatPack pack = findCompatPackById(compatPackManager.listInstalledPacks(), compatPackId, compatTargetId);
		if (pack == null) {
			return getString(R.string.launch_profile_compat_missing_format, TextUtils.isEmpty(compatTargetId) ? compatPackId : compatPackId + "/" + compatTargetId);
		}
		return getString(R.string.version_manager_selected_compat_format, pack.displayName, pack.targetLabel());
	}

	private CompatPackManager.CompatPack findCompatPackById(List<CompatPackManager.CompatPack> packs, String packId) {
		return findCompatPackById(packs, packId, "");
	}

	private CompatPackManager.CompatPack findCompatPackById(List<CompatPackManager.CompatPack> packs, String packId, String targetId) {
		if (TextUtils.isEmpty(packId) || packs == null) {
			return null;
		}
		for (CompatPackManager.CompatPack pack : packs) {
			if (!pack.packId.equals(packId)) {
				continue;
			}
			if (!TextUtils.isEmpty(targetId) && targetId.equals(pack.targetId)) {
				return pack;
			}
			if (TextUtils.isEmpty(targetId) && pack.manifest.optInt("schema", 1) < 2) {
				return pack;
			}
		}
		return null;
	}

	private boolean stringsEqual(String left, String right) {
		String normalizedLeft = left == null ? "" : left;
		String normalizedRight = right == null ? "" : right;
		return normalizedLeft.equals(normalizedRight);
	}

	private void showLaunchProfileCompatPicker(String currentCompatPackId, String currentCompatTargetId, LaunchProfileManager.GamePayload selectedPayload, LaunchProfileCompatPickerCallback callback) {
		try {
			List<CompatPackManager.CompatPack> packs = compatPackManager.listInstalledPacks();
			List<CompatPick> picks = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			picks.add(new CompatPick("", ""));
			labels.add(getString(R.string.launch_profile_select_compat_none));
			int checked = 0;
			for (CompatPackManager.CompatPack pack : packs) {
				picks.add(new CompatPick(pack.packId, pack.targetId));
				String label = getString(R.string.version_manager_selected_compat_format, pack.displayName, pack.targetLabel());
				if (selectedPayload != null && compatPackManager.isPackCompatibleWithPayload(pack, selectedPayload.manifest)) {
					if (compatPackManager.isOfflineBootstrapPack(pack)) {
						if (compatPackManager.hasKnownFailedOfflineBootstrap(pack, selectedPayload.manifest)) {
							label = label + " " + getString(R.string.launch_profile_compat_offline_failed_badge);
						} else if (compatPackManager.hasVerifiedOfflineBootstrap(pack, selectedPayload.manifest)) {
							label = label + " " + getString(R.string.launch_profile_compat_offline_verified_badge);
						} else {
							label = label + " " + getString(R.string.launch_profile_compat_offline_fallback_badge);
						}
					} else {
						label = label + " ✓";
					}
				}
				labels.add(label);
				if (pack.packId.equals(currentCompatPackId) && stringsEqual(pack.targetId, currentCompatTargetId)) {
					checked = picks.size() - 1;
				}
			}
			new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.launch_profile_select_compat_title)
				.setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
					callback.onCompatPicked(picks.get(which));
					dialog.dismiss();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private interface LaunchProfileCompatPickerCallback {
		void onCompatPicked(CompatPick pick);
	}

	private static final class CompatPick {
		final String packId;
		final String targetId;

		CompatPick(String packId, String targetId) {
			this.packId = packId == null ? "" : packId;
			this.targetId = targetId == null ? "" : targetId;
		}
	}

	private void showLaunchProfilePayloadPicker(LaunchProfileManager.GamePayload currentPayload, LaunchProfilePayloadPickerCallback callback) {
		try {
			List<LaunchProfileManager.GamePayload> payloads = launchProfileManager.listPayloads();
			List<LaunchProfileManager.GamePayload> readyPayloads = new ArrayList<>();
			for (LaunchProfileManager.GamePayload payload : payloads) {
				if (payload != null && payload.ready) {
					readyPayloads.add(payload);
				}
			}
			if (readyPayloads.isEmpty()) {
				showMessage(getString(R.string.version_manager_no_archived_games));
				return;
			}
			String[] labels = new String[readyPayloads.size()];
			int checked = 0;
			for (int i = 0; i < readyPayloads.size(); i++) {
				LaunchProfileManager.GamePayload payload = readyPayloads.get(i);
				labels[i] = payload.label;
				if (currentPayload != null && payload.id.equals(currentPayload.id)) {
					checked = i;
				}
			}
			new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.launch_profile_select_payload_title)
				.setSingleChoiceItems(labels, checked, (dialog, which) -> {
					callback.onPayloadPicked(readyPayloads.get(which));
					dialog.dismiss();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private interface LaunchProfilePayloadPickerCallback {
		void onPayloadPicked(LaunchProfileManager.GamePayload payload);
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
	public void openModStore() {
		try {
			startActivity(new Intent(this, NexusModsStoreActivity.class));
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	public void openSteamWorkshop() {
		try {
			startActivity(new Intent(this, SteamWorkshopActivity.class));
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	public void openSteamAccount() {
		try {
			startActivityForResult(new Intent(this, SteamAccountActivity.class), REQUEST_STEAM_PAYLOAD_RESULT);
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	public void openWebDavCloud() {
		try {
			startActivity(new Intent(this, WebDavCloudActivity.class));
		} catch (Exception exception) {
			showError(exception);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode != RESULT_OK || data == null) {
			return;
		}
		// Android can redeliver the picker's result to a recreated Activity before it resumes.
		if (!directoriesReady) {
			pendingPickerRequest = requestCode;
			pendingPickerResult = data;
			return;
		}
		handlePickerResult(requestCode, data);
	}

	private void handlePickerResult(int requestCode, Intent data) {
		if (requestCode == REQUEST_STEAM_PAYLOAD_RESULT) {
			refreshCurrentScreen();
			maybeCheckCompatAfterPayloadImport();
		} else if (requestCode == REQUEST_EXPORT_SAVE && data.getData() != null) {
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
			if (!uris.isEmpty()) {
				prepareModImportsWithConflictDialogs(uris);
			}
		}
	}

	private void prepareModImportsWithConflictDialogs(List<Uri> uris) {
		if (busy || uris == null || uris.isEmpty() || isModImportUiUnavailable()) return;
		busy = true;
		modImportNotices.clear();
		showMessage(getString(R.string.status_busy_import_mod));
		MOD_IMPORT_EXECUTOR.execute(() -> {
			List<ExtraSettingsRepository.PreparedModImport> preparedImports = new ArrayList<>();
			try {
				for (Uri uri : uris) {
					if (importDestroyed) break;
					preparedImports.add(repository.prepareModImport(uri));
				}
				runOnUiThread(() -> {
					if (isModImportUiUnavailable()) {
						discardModImports(preparedImports);
						return;
					}
					activeModImports = preparedImports;
					handlePreparedModImports(preparedImports, 0, 0, "");
				});
			} catch (Exception exception) {
				for (ExtraSettingsRepository.PreparedModImport prepared : preparedImports) repository.discardPreparedModImport(prepared);
				runOnUiThread(() -> {
					busy = false;
					if (!isModImportUiUnavailable()) showError(exception);
				});
			}
		});
	}

	private void handlePreparedModImports(List<ExtraSettingsRepository.PreparedModImport> imports, int index, int importedCount, String lastName) {
		if (isModImportUiUnavailable()) {
			discardModImports(imports);
			return;
		}
		if (index >= imports.size()) {
			busy = false;
			activeModImports = new ArrayList<>();
			refreshCurrentScreen();
			if (importedCount == 1 && !TextUtils.isEmpty(lastName)) {
				showMessage(getString(R.string.status_import_mod_done) + " " + lastName);
			} else if (importedCount > 0) {
				showMessage(getString(R.string.status_import_mod_done_count, importedCount));
			} else {
				showMessage(getString(R.string.status_import_mod_cancelled));
			}
			showModImportNotices();
			return;
		}
		ExtraSettingsRepository.PreparedModImport prepared = imports.get(index);
		MOD_IMPORT_EXECUTOR.execute(() -> {
			try {
				List<ExtraSettingsRepository.ModImportConflict> conflicts = repository.findCurrentImportConflicts(prepared);
				runOnUiThread(() -> {
					if (isModImportUiUnavailable()) return;
					if (conflicts.isEmpty()) {
						handlePreparedModImportPathConflicts(imports, index, importedCount, lastName, false, conflicts);
					} else {
						Runnable skip = () -> skipPreparedModImport(imports, index, importedCount, lastName);
						showModImportConflictDialog(prepared, conflicts, skip,
							() -> handlePreparedModImportPathConflicts(imports, index, importedCount, lastName, true, conflicts), skip);
					}
				});
			} catch (Exception exception) {
				failModImports(imports, exception);
			}
		});
	}

	private void skipPreparedModImport(List<ExtraSettingsRepository.PreparedModImport> imports, int index, int importedCount, String lastName) {
		discardModImports(java.util.Collections.singletonList(imports.get(index)));
		handlePreparedModImports(imports, index + 1, importedCount, lastName);
	}

	private void handlePreparedModImportPathConflicts(List<ExtraSettingsRepository.PreparedModImport> imports, int index, int importedCount, String lastName, boolean replaceExistingConflicts, List<ExtraSettingsRepository.ModImportConflict> confirmedIdConflicts) {
		ExtraSettingsRepository.PreparedModImport prepared = imports.get(index);
		MOD_IMPORT_EXECUTOR.execute(() -> {
			try {
				List<ExtraSettingsRepository.ModImportPathConflict> conflicts = repository.findCurrentImportPathConflicts(prepared, replaceExistingConflicts ? confirmedIdConflicts : new ArrayList<>());
				runOnUiThread(() -> {
					if (isModImportUiUnavailable()) return;
					if (conflicts.isEmpty()) {
						commitPreparedModImport(imports, index, importedCount, lastName, replaceExistingConflicts, false);
					} else {
						Runnable skip = () -> skipPreparedModImport(imports, index, importedCount, lastName);
						showModImportPathConflictDialog(prepared, conflicts, skip,
							() -> commitPreparedModImport(imports, index, importedCount, lastName, replaceExistingConflicts, true), skip);
					}
				});
			} catch (Exception exception) {
				failModImports(imports, exception);
			}
		});
	}

	private void failModImports(List<ExtraSettingsRepository.PreparedModImport> imports, Exception exception) {
		for (ExtraSettingsRepository.PreparedModImport prepared : imports) repository.discardPreparedModImport(prepared);
		runOnUiThread(() -> {
			busy = false;
			activeModImports = new ArrayList<>();
			if (!isModImportUiUnavailable()) {
				refreshCurrentScreen();
				showError(exception);
				showModImportNotices();
			}
		});
	}

	private void commitPreparedModImport(List<ExtraSettingsRepository.PreparedModImport> imports, int index, int importedCount, String lastName, boolean replaceExistingConflicts, boolean allowPathConflicts) {
		if (isModImportUiUnavailable()) return;
		ExtraSettingsRepository.PreparedModImport prepared = imports.get(index);
		MOD_IMPORT_EXECUTOR.execute(() -> {
			try {
				if (importDestroyed) return;
				String importedName = repository.commitPreparedModImport(prepared, replaceExistingConflicts, allowPathConflicts);
				String notice = prepared.getFileNotice();
				runOnUiThread(() -> {
					if (isModImportUiUnavailable()) return;
					if (!TextUtils.isEmpty(notice)) modImportNotices.add(importedName + "\n" + notice);
					handlePreparedModImports(imports, index + 1, importedCount + 1, importedName);
				});
			} catch (Exception exception) {
				failModImports(imports, exception);
			}
		});
	}

	private void showModImportConflictDialog(ExtraSettingsRepository.PreparedModImport preparedImport, List<ExtraSettingsRepository.ModImportConflict> conflicts, Runnable keepOriginal, Runnable useNew, Runnable cancelImport) {
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		TextView message = ExtraSettingsUi.body(this, getString(R.string.mod_import_conflict_message));
		content.addView(message);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.mod_import_replacement_notice)));
		if (preparedImport.fullPackage) {
			MaterialCheckBox fullUpdate = new MaterialCheckBox(this);
			fullUpdate.setText(R.string.mod_import_replace_tracked_files);
			fullUpdate.setChecked(preparedImport.isReplaceTrackedFiles());
			fullUpdate.setOnCheckedChangeListener((button, checked) -> preparedImport.setReplaceTrackedFiles(checked));
			content.addView(fullUpdate);
		}
		for (ExtraSettingsRepository.ModImportConflict conflict : conflicts) {
			TextView conflictTitle = ExtraSettingsUi.text(this, getString(R.string.mod_import_conflict_id_format, conflict.modId), 15, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD);
			LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			titleParams.topMargin = ExtraSettingsUi.dp(this, 12);
			content.addView(conflictTitle, titleParams);
			if (!conflict.existingEntries.isEmpty()) {
				content.addView(buildImportConflictInfoCard(getString(R.string.mod_import_conflict_original), conflict.existingEntries.get(0)));
			}
			content.addView(buildImportConflictInfoCard(getString(R.string.mod_import_conflict_incoming), conflict.incomingEntry));
		}
		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		modImportDialog = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.mod_import_conflict_title)
			.setView(scrollView)
			.setNegativeButton(R.string.mod_import_conflict_keep_original, (dialog, which) -> keepOriginal.run())
			.setNeutralButton(android.R.string.cancel, (dialog, which) -> cancelImport.run())
			.setPositiveButton(R.string.mod_import_conflict_use_new, (dialog, which) -> useNew.run())
			.setOnCancelListener(dialog -> cancelImport.run())
			.show();
	}

	private void showModImportPathConflictDialog(ExtraSettingsRepository.PreparedModImport preparedImport, List<ExtraSettingsRepository.ModImportPathConflict> conflicts, Runnable keepInstalled, Runnable replaceFiles, Runnable cancelImport) {
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.mod_import_path_conflict_message)));
		int visibleCount = Math.min(12, conflicts.size());
		for (int i = 0; i < visibleCount; i++) {
			ExtraSettingsRepository.ModImportPathConflict conflict = conflicts.get(i);
			MaterialCardView card = ExtraSettingsUi.card(this);
			card.setRadius(ExtraSettingsUi.dp(this, 16));
			card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
			card.setStrokeColor(ExtraSettingsUi.COLOR_OUTLINE);
			card.setStrokeWidth(ExtraSettingsUi.dp(this, 1));
			LinearLayout cardContent = ExtraSettingsUi.cardContent(this, card);
			cardContent.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
			cardContent.addView(ExtraSettingsUi.text(this, conflict.relativePath, 14, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
			if (!TextUtils.isEmpty(conflict.existingOwnerLabel)) {
				cardContent.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_import_path_conflict_owner, conflict.existingOwnerLabel)));
			}
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			params.topMargin = ExtraSettingsUi.dp(this, 8);
			content.addView(card, params);
		}
		if (conflicts.size() > visibleCount) {
			content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_import_path_conflict_more, conflicts.size() - visibleCount)));
		}
		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		modImportDialog = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.mod_import_path_conflict_title)
			.setView(scrollView)
			.setNegativeButton(R.string.mod_import_path_conflict_keep_installed, (dialog, which) -> keepInstalled.run())
			.setNeutralButton(android.R.string.cancel, (dialog, which) -> cancelImport.run())
			.setPositiveButton(R.string.mod_import_path_conflict_replace_files, (dialog, which) -> replaceFiles.run())
			.setOnCancelListener(dialog -> cancelImport.run())
			.show();
	}

	private View buildImportConflictInfoCard(String label, ExtraSettingsRepository.ModEntry entry) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		card.setStrokeColor(ExtraSettingsUi.COLOR_OUTLINE);
		card.setStrokeWidth(ExtraSettingsUi.dp(this, 1));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
		content.addView(ExtraSettingsUi.text(this, label, 15, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
		content.addView(ExtraSettingsUi.body(this, entry.displayName));
		content.addView(ExtraSettingsUi.caption(this, "ID: " + entry.modId));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_version) + ": " + emptyToDash(entry.version)));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_author) + ": " + emptyToDash(entry.authors)));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_path) + ": " + entry.relativePath));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(this, 8);
		card.setLayoutParams(params);
		return card;
	}

	private String emptyToDash(String value) {
		return TextUtils.isEmpty(value) ? "—" : value;
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
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					refreshCurrentScreen();
					if (!bundled) maybeCheckCompatAfterPayloadImport();
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

	private void showLaunchProfilePayloadMissingDialog(LaunchProfileManager.LaunchProfile profile) {
		String payloadLabel = TextUtils.isEmpty(profile.payloadId) ? getString(R.string.unknown) : profile.payloadId;
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.launch_profile_payload_missing_launch_title)
			.setMessage(getString(R.string.launch_profile_payload_missing_launch_message, profile.displayName, payloadLabel))
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.version_manager_tab_profiles, (dialog, which) -> {
				GameVersionManagerPage.selectProfilesTab();
				openVersionsTab();
			})
			.setPositiveButton(R.string.import_game_payload, (dialog, which) -> requestImportGamePayload())
			.show();
	}

	private void showMissingCompatPackSheet(PayloadManager.Status payloadStatus, LaunchProfileManager.LaunchProfile profile) {
		List<CompatPackManager.CompatPack> installedPacks = compatPackManager.listInstalledPacks();
		CompatRecommendation recommendation = findCompatRecommendation(payloadStatus, installedPacks);
		CompatLaunchSelection selection = new CompatLaunchSelection(profile, payloadStatus);
		BottomSheetDialog dialog = new BottomSheetDialog(this);
		dialog.setOnShowListener(unused -> {
			if (dialog.getWindow() != null) {
				dialog.getWindow().setDimAmount(0.56f);
			}
		});

		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 32));
		content.addView(ExtraSettingsUi.text(this, R.string.launch_compat_recommendation_sheet_title, 22, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
		TextView introduction = ExtraSettingsUi.body(this, R.string.launch_compat_recommendation_sheet_message);
		LinearLayout.LayoutParams introductionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		introductionParams.topMargin = ExtraSettingsUi.dp(this, 8);
		content.addView(introduction, introductionParams);

		TextView gameIdentity = ExtraSettingsUi.caption(this, buildCompatRecommendationPayloadLabel(payloadStatus));
		gameIdentity.setTextIsSelectable(true);
		LinearLayout.LayoutParams gameIdentityParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		gameIdentityParams.topMargin = ExtraSettingsUi.dp(this, 10);
		content.addView(gameIdentity, gameIdentityParams);
		LinearLayout online = ExtraSettingsUi.vertical(this);
		if (recommendation == null || recommendation.kind == CompatRecommendationKind.OFFLINE) {
			ExtraSettingsUi.addSmallSpacing(content, online);
		}

		View recommendationCard = recommendation == null
			? buildNoCompatRecommendationCard()
			: buildCompatRecommendationCard(recommendation);
		LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		cardParams.topMargin = ExtraSettingsUi.dp(this, 18);
		content.addView(recommendationCard, cardParams);

		LinearLayout actions = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		actionsParams.topMargin = ExtraSettingsUi.dp(this, 20);
		content.addView(actions, actionsParams);

		if (recommendation != null) {
			int useLabel = recommendation.kind == CompatRecommendationKind.OFFLINE
				? R.string.launch_compat_use_offline_recommendation
				: R.string.launch_compat_use_recommendation;
			MaterialButton useRecommendation = ExtraSettingsUi.filledButton(this, useLabel, R.drawable.ic_check_circle_24);
			useRecommendation.setAllCaps(false);
			useRecommendation.setSingleLine(false);
			useRecommendation.setEllipsize(null);
			useRecommendation.setMinHeight(ExtraSettingsUi.dp(this, 48));
			useRecommendation.setOnClickListener(v -> applyCompatRecommendationAndContinue(dialog, recommendation, selection));
			actions.addView(useRecommendation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}

		MaterialButton manage = ExtraSettingsUi.outlineButton(this, R.string.launch_compat_open_management, R.drawable.ic_layers_24);
		manage.setAllCaps(false);
		manage.setSingleLine(false);
		manage.setEllipsize(null);
		manage.setMinHeight(ExtraSettingsUi.dp(this, 48));
		manage.setOnClickListener(v -> {
			dialog.dismiss();
			openCompatPackManagement();
		});
		LinearLayout.LayoutParams manageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		manageParams.topMargin = recommendation == null ? 0 : ExtraSettingsUi.dp(this, 10);
		actions.addView(manage, manageParams);

		ScrollableBottomSheet.setContent(dialog, content);
		dialog.show();
		observeCompatLaunchDialog(dialog, online, selection, payloadStatus, installedPacks, false);
	}

	private CompatRecommendation findCompatRecommendation(PayloadManager.Status payloadStatus, List<CompatPackManager.CompatPack> installedPacks) {
		if (payloadStatus == null || payloadStatus.manifest == null) {
			return null;
		}
		List<CompatPackManager.CompatPack> fullPacks = new ArrayList<>();
		List<CompatPackManager.CompatPack> offlinePacks = new ArrayList<>();
		for (CompatPackManager.CompatPack pack : installedPacks) {
			if (pack == null || !pack.ready) {
				continue;
			}
			if (pack.isOfflineWildcard()) {
				offlinePacks.add(pack);
			} else if (!compatPackManager.isOfflineBootstrapPack(pack)) {
				fullPacks.add(pack);
			}
		}

		CompatPackManager.CompatPack match = compatPackManager.findBestMatch(payloadStatus.manifest, fullPacks);
		if (match != null) {
			return new CompatRecommendation(match, isBundledCompatPack(match) ? CompatRecommendationKind.BUNDLED : CompatRecommendationKind.INSTALLED);
		}
		match = compatPackManager.findBestMatch(payloadStatus.manifest, offlinePacks);
		return match == null ? null : new CompatRecommendation(match, CompatRecommendationKind.OFFLINE);
	}

	private boolean isBundledCompatPack(CompatPackManager.CompatPack pack) {
		return pack != null
			&& pack.manifest.optJSONObject("installed_source") != null
			&& "bundled_asset".equals(pack.manifest.optJSONObject("installed_source").optString("kind", ""));
	}

	private View buildCompatRecommendationCard(CompatRecommendation recommendation) {
		boolean offline = recommendation.kind == CompatRecommendationKind.OFFLINE;
		int accent = offline ? ExtraSettingsUi.COLOR_WARNING : ExtraSettingsUi.COLOR_PRIMARY;
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setCardBackgroundColor(offline ? Color.rgb(54, 43, 31) : Color.rgb(30, 50, 39));
		card.setStrokeColor(accent);
		card.setStrokeWidth(ExtraSettingsUi.dp(this, 1));
		LinearLayout cardContent = ExtraSettingsUi.cardContent(this, card);
		cardContent.setPadding(ExtraSettingsUi.dp(this, 18), ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 18), ExtraSettingsUi.dp(this, 16));

		LinearLayout header = ExtraSettingsUi.horizontal(this);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.addView(ExtraSettingsUi.iconCircle(
			this,
			offline ? R.drawable.ic_extension_24 : R.drawable.ic_auto_awesome_24,
			offline ? Color.rgb(91, 67, 35) : ExtraSettingsUi.COLOR_PRIMARY_CONTAINER,
			offline ? ExtraSettingsUi.COLOR_WARNING : ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER
		));

		LinearLayout labels = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		labelsParams.setMarginStart(ExtraSettingsUi.dp(this, 14));
		header.addView(labels, labelsParams);
		int badgeRes = recommendation.kind == CompatRecommendationKind.BUNDLED
			? R.string.launch_compat_recommendation_bundled_badge
			: (recommendation.kind == CompatRecommendationKind.INSTALLED
				? R.string.launch_compat_recommendation_installed_badge
				: R.string.launch_compat_recommendation_offline_badge);
		labels.addView(ExtraSettingsUi.text(this, badgeRes, 11, accent, android.graphics.Typeface.BOLD));
		TextView packName = ExtraSettingsUi.text(this, recommendation.pack.displayName, 17, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD);
		packName.setLineSpacing(ExtraSettingsUi.dp(this, 2), 1f);
		LinearLayout.LayoutParams packNameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		packNameParams.topMargin = ExtraSettingsUi.dp(this, 3);
		labels.addView(packName, packNameParams);
		cardContent.addView(header);

		TextView target = ExtraSettingsUi.body(this, getString(
			R.string.launch_compat_recommendation_target_format,
			compatRecommendationTargetName(recommendation.pack),
			compatRecommendationVersions(recommendation.pack)
		));
		LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		targetParams.topMargin = ExtraSettingsUi.dp(this, 12);
		cardContent.addView(target, targetParams);

		int descriptionRes = recommendation.kind == CompatRecommendationKind.BUNDLED
			? R.string.launch_compat_recommendation_bundled_description
			: (recommendation.kind == CompatRecommendationKind.INSTALLED
				? R.string.launch_compat_recommendation_installed_description
				: R.string.launch_compat_recommendation_offline_description);
		TextView description = ExtraSettingsUi.body(this, descriptionRes);
		description.setTextColor(offline ? ExtraSettingsUi.COLOR_WARNING : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		descriptionParams.topMargin = ExtraSettingsUi.dp(this, 8);
		cardContent.addView(description, descriptionParams);
		return card;
	}

	private View buildNoCompatRecommendationCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setCardBackgroundColor(Color.rgb(54, 43, 31));
		card.setStrokeColor(ExtraSettingsUi.COLOR_WARNING);
		LinearLayout cardContent = ExtraSettingsUi.cardContent(this, card);
		cardContent.setPadding(ExtraSettingsUi.dp(this, 18), ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 18), ExtraSettingsUi.dp(this, 16));
		cardContent.addView(ExtraSettingsUi.text(this, R.string.launch_compat_no_recommendation_title, 16, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
		TextView description = ExtraSettingsUi.body(this, R.string.launch_compat_no_recommendation_message);
		description.setTextColor(ExtraSettingsUi.COLOR_WARNING);
		LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		descriptionParams.topMargin = ExtraSettingsUi.dp(this, 6);
		cardContent.addView(description, descriptionParams);
		return card;
	}

	private String buildCompatRecommendationPayloadLabel(PayloadManager.Status payloadStatus) {
		String version = payloadStatus == null || payloadStatus.manifest == null ? "" : compatPackManager.getPayloadVersion(payloadStatus.manifest);
		if (TextUtils.isEmpty(version) && payloadStatus != null) {
			version = payloadStatus.shortVersionLabel();
		}
		if (TextUtils.isEmpty(version)) {
			version = getString(R.string.unknown);
		}
		String dllSha = payloadStatus == null || payloadStatus.manifest == null ? "" : compatPackManager.getPayloadSts2DllSha256(payloadStatus.manifest);
		if (!TextUtils.isEmpty(dllSha) && dllSha.length() > 12) {
			dllSha = dllSha.substring(0, 12);
		}
		if (TextUtils.isEmpty(dllSha)) {
			dllSha = getString(R.string.unknown);
		}
		return getString(R.string.launch_compat_recommendation_game_format, version, dllSha);
	}

	private String compatRecommendationTargetName(CompatPackManager.CompatPack pack) {
		if (pack == null) {
			return getString(R.string.unknown);
		}
		if (!TextUtils.isEmpty(pack.targetDisplayName)) {
			return pack.targetDisplayName;
		}
		if (!TextUtils.isEmpty(pack.targetId)) {
			return pack.targetId;
		}
		return pack.displayName;
	}

	private String compatRecommendationVersions(CompatPackManager.CompatPack pack) {
		if (pack == null) {
			return getString(R.string.unknown);
		}
		if (pack.isOfflineWildcard()) {
			return getString(R.string.version_manager_compat_any_version);
		}
		if (pack.targetVersions != null && !pack.targetVersions.isEmpty()) {
			return TextUtils.join(" / ", pack.targetVersions);
		}
		if (!TextUtils.isEmpty(pack.targetVersion)) {
			return pack.targetVersion;
		}
		if (!TextUtils.isEmpty(pack.targetSts2DllSha256)) {
			return pack.targetSts2DllSha256.substring(0, Math.min(12, pack.targetSts2DllSha256.length()));
		}
		return getString(R.string.unknown);
	}

	private void applyCompatRecommendationAndContinue(Dialog dialog, CompatRecommendation recommendation, CompatLaunchSelection selection) {
		if (busy || !dialog.isShowing()) return;
		LaunchProfileManager.LaunchProfile current = selection.current(null, null);
		if (current == null) return;
		try {
			CompatPackManager.CompatPack pack = recommendation.pack;
			boolean ready = compatPackManager.listInstalledPacks().stream().anyMatch(installed -> installed.ready
				&& installed.packId.equals(pack.packId) && installed.targetId.equals(pack.targetId)
				&& compatPackManager.isPackCompatibleWithPayload(installed, current.payload.manifest));
			if (!ready) {
				dialog.dismiss();
				if (selection.current(null, null) != null) launchGame();
				return;
			}
			bindCompatAndLaunch(dialog, selection, pack, null, null);
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private boolean bindCompatAndLaunch(Dialog dialog, CompatLaunchSelection selection, CompatPackManager.CompatPack pack,
			CompatPackRelease release, CompatPackReleaseTarget target) throws Exception {
		if (!selection.isCurrentScreen()) return false;
		boolean bound = launchProfileManager.updateSelectedProfileCompatIf(selection.profile.id,
			current -> selection.matches(current, release, target), pack.packId, pack.targetId, LaunchProfileManager.COMPAT_SELECTION_AUTO);
		if (!bound) return false;
		dialog.dismiss();
		refreshCurrentScreen();
		if (selection.isCurrentScreen()) {
			LaunchProfileManager.LaunchProfile current = launchProfileManager.getSelectedProfile();
			if (selection.sameProfileAndPayload(current) && pack.packId.equals(current.compatPackId)
				&& pack.targetId.equals(current.compatTargetId)
				&& LaunchProfileManager.COMPAT_SELECTION_AUTO.equals(current.compatSelectionSource)) launchGame();
		}
		return true;
	}

	private final class CompatLaunchSelection {
		private final LaunchProfileManager.LaunchProfile profile;
		private final int tab = currentTabId;
		private final long navigation = compatNavigationGeneration;
		private final long versionTab = GameVersionManagerPage.tabGeneration();

		CompatLaunchSelection(LaunchProfileManager.LaunchProfile profile, PayloadManager.Status payload) {
			this.profile = profile != null && profile.payload != null && profile.payload.gameDir.equals(payload.gameDir) ? profile : null;
		}

		boolean isCurrentScreen() {
			return !isFinishing() && !isDestroyed() && tab == currentTabId && navigation == compatNavigationGeneration
				&& (tab != R.id.nav_versions || versionTab == GameVersionManagerPage.tabGeneration())
				&& profile != null && profile.id.equals(launchProfileManager.getSelectedProfileId());
		}

		LaunchProfileManager.LaunchProfile current(CompatPackRelease release, CompatPackReleaseTarget target) {
			if (!isCurrentScreen()) return null;
			LaunchProfileManager.LaunchProfile current = launchProfileManager.getSelectedProfile();
			return matches(current, release, target) ? current : null;
		}

		boolean sameProfileAndPayload(LaunchProfileManager.LaunchProfile current) {
			return current != null && current.ready && current.payload != null && profile.id.equals(current.id)
				&& profile.createdAtUnix == current.createdAtUnix && profile.displayName.equals(current.displayName)
				&& profile.saveMode.equals(current.saveMode) && profile.modsMode.equals(current.modsMode)
				&& profile.payloadId.equals(current.payloadId)
				&& profile.payload.installedAtUnix == current.payload.installedAtUnix
				&& profile.payload.version.equals(current.payload.version) && profile.payload.commit.equals(current.payload.commit)
				&& profile.payload.sts2DllSha256.equals(current.payload.sts2DllSha256)
				&& profile.payload.pckSha256.equals(current.payload.pckSha256);
		}

		boolean matches(LaunchProfileManager.LaunchProfile current, CompatPackRelease release, CompatPackReleaseTarget target) {
			if (!sameProfileAndPayload(current) || !compatPackManager.isCompatPackEnabled()) return false;
			boolean sameBinding = profile.compatSelectionSource.equals(current.compatSelectionSource)
				&& profile.compatPackId.equals(current.compatPackId) && profile.compatTargetId.equals(current.compatTargetId);
			// A verified install may itself resolve the exact AUTO binding this action requested.
			boolean upgradedByInstall = release != null && target != null
				&& LaunchProfileManager.COMPAT_SELECTION_AUTO.equals(profile.compatSelectionSource)
				&& LaunchProfileManager.COMPAT_SELECTION_AUTO.equals(current.compatSelectionSource)
				&& release.packId.equals(current.compatPackId) && target.targetId.equals(current.compatTargetId);
			return sameBinding || upgradedByInstall;
		}
	}

	private void openCompatPackManagement() {
		GameVersionManagerPage.selectCompatTab();
		if (contentFrame == null) {
			showMainShell(R.id.nav_versions);
			return;
		}
		selectNavigationItem(R.id.nav_versions);
	}

	private void showCompatDisabledLaunchWarning() {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.launch_compat_disabled_dialog_title)
			.setMessage(R.string.launch_compat_disabled_dialog_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.enable_compat_pack, (dialog, which) -> {
				runAsyncOperation(getString(R.string.status_settings_saved), () -> {
					repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, true));
					return getString(R.string.status_settings_saved);
				});
			})
			.setPositiveButton(R.string.launch_anyway, (dialog, which) -> prepareAndStartGameAfterOptionalSteamCloudPull())
			.show();
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
			.setNeutralButton(R.string.version_manager_tab_profiles, (dialog, which) -> {
				GameVersionManagerPage.selectProfilesTab();
				openVersionsTab();
			})
			.setPositiveButton(R.string.launch_anyway, (dialog, which) -> prepareAndStartGame())
			.show();
	}

	private void showOfflineBootstrapLaunchWarning(PayloadManager.Status payloadStatus, CompatPackManager.CompatPack selectedCompatPack,
			LaunchProfileManager.LaunchProfile profile) {
		String payloadVersion = compatPackManager.getPayloadVersion(payloadStatus.manifest);
		if (TextUtils.isEmpty(payloadVersion)) {
			payloadVersion = payloadStatus.shortVersionLabel();
		}
		String dllSha = compatPackManager.getPayloadSts2DllSha256(payloadStatus.manifest);
		if (!TextUtils.isEmpty(dllSha) && dllSha.length() > 12) {
			dllSha = dllSha.substring(0, 12);
		}
		if (TextUtils.isEmpty(dllSha)) {
			dllSha = getString(R.string.unknown);
		}

		CompatPackManager.OfflineBootstrapProbe probe = compatPackManager.getOfflineBootstrapProbe(selectedCompatPack, payloadStatus.manifest);
		boolean knownFailure = probe != null && probe.isTerminalFailure();
		String title;
		String message;
		int positiveLabel;
		if (knownFailure) {
			String failure = TextUtils.isEmpty(probe.failureSummary) ? getString(R.string.unknown) : probe.failureSummary;
			if (failure.length() > 1600) {
				failure = failure.substring(0, 1600) + "…";
			}
			title = getString(R.string.launch_offline_bootstrap_failed_dialog_title);
			message = getString(R.string.launch_offline_bootstrap_failed_dialog_message, selectedCompatPack.displayName, payloadVersion, dllSha, probe.status, failure);
			positiveLabel = R.string.launch_offline_bootstrap_retry;
		} else {
			title = getString(R.string.launch_offline_bootstrap_dialog_title);
			message = getString(R.string.launch_offline_bootstrap_dialog_message, selectedCompatPack.displayName, selectedCompatPack.targetLabel(), payloadVersion, dllSha);
			positiveLabel = R.string.launch_offline_bootstrap_confirm;
		}

		// Keep every choice reachable even when probe details need most of the dialog height.
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(ExtraSettingsUi.dp(this, 24), 0, ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 8));
		content.addView(ExtraSettingsUi.body(this, message));
		AlertDialog warningDialog = ExtraSettingsUi.scrollableDialog(this, content)
			.setTitle(title)
			.setNegativeButton(android.R.string.cancel, null)
			.create();
		List<CompatPackManager.CompatPack> installedPacks = compatPackManager.listInstalledPacks();
		CompatRecommendation recommendation = findCompatRecommendation(payloadStatus, installedPacks);
		CompatLaunchSelection selection = new CompatLaunchSelection(profile, payloadStatus);
		LinearLayout online = ExtraSettingsUi.vertical(this);
		if (recommendation != null && recommendation.kind != CompatRecommendationKind.OFFLINE) {
			MaterialButton switchPack = ExtraSettingsUi.filledButton(this, R.string.launch_offline_bootstrap_switch_matching, R.drawable.ic_check_circle_24);
			switchPack.setText(getString(R.string.launch_offline_bootstrap_switch_matching, compatRecommendationVersions(recommendation.pack)));
			switchPack.setAllCaps(false);
			switchPack.setSingleLine(false);
			switchPack.setMinHeight(ExtraSettingsUi.dp(this, 48));
			switchPack.setOnClickListener(view -> applyCompatRecommendationAndContinue(warningDialog, recommendation, selection));
			ExtraSettingsUi.addSmallSpacing(content, switchPack);
		} else {
			ExtraSettingsUi.addSmallSpacing(content, online);
		}
		MaterialButton useOffline = ExtraSettingsUi.outlineButton(this, positiveLabel, 0);
		useOffline.setAllCaps(false);
		useOffline.setSingleLine(false);
		useOffline.setOnClickListener(view -> {
			if (busy || selection.current(null, null) == null || !warningDialog.isShowing()) return;
			warningDialog.dismiss();
			compatPackManager.approveOfflineBootstrap(selectedCompatPack, payloadStatus.manifest);
			prepareAndStartGameAfterOptionalSteamCloudPull();
		});
		ExtraSettingsUi.addSmallSpacing(content, useOffline);
		MaterialButton editProfile = ExtraSettingsUi.outlineButton(this, R.string.version_manager_tab_profiles, 0);
		editProfile.setAllCaps(false);
		editProfile.setSingleLine(false);
		editProfile.setOnClickListener(view -> {
			warningDialog.dismiss();
			GameVersionManagerPage.selectProfilesTab();
			openVersionsTab();
		});
		ExtraSettingsUi.addSmallSpacing(content, editProfile);
		warningDialog.show();
		observeCompatLaunchDialog(warningDialog, online, selection, payloadStatus, installedPacks, true);
	}

	private void finishCompatBootstrap(boolean installed) {
		bundledCompatPackBootstrapFinished = true;
		if (installed && resumed) refreshCurrentScreen();
		maybeCheckCompatOnStartup();
		scheduleStartupContinuation();
	}

	private void maybeCheckCompatOnStartup() {
		if (compatImportCheckPending) maybeCheckCompatAfterPayloadImport();
		if (startupCompatCheckRequested || !bundledCompatPackBootstrapFinished || !canCheckCompatInBackground()) return;
		startupCompatCheckRequested = true;
		checkCompatPackUpdatesInBackground(false);
	}

	private boolean canCheckCompatInBackground() {
		return compatReleaseRepository != null && !isFinishing() && !isDestroyed()
			&& ExtraSettingsPreferences.isFirstRunSetupCompleted(this) && compatPackManager.isCompatPackEnabled();
	}

	private void maybeCheckCompatAfterPayloadImport() {
		if (!bundledCompatPackBootstrapFinished || !canCheckCompatInBackground()) {
			compatImportCheckPending = true;
			return;
		}
		compatImportCheckPending = false;
		new Thread(() -> {
			LaunchProfileManager.LaunchProfile profile = launchProfileManager.getSelectedProfile();
			if (profile == null || !profile.ready || profile.payload == null) return;
			CompatPackManager.CompatPack local = compatPackManager.findBestMatch(profile.payload.manifest);
			if (local != null && !compatPackManager.isOfflineBootstrapPack(local)) return;
			runOnUiThreadIfActive(() -> {
				if (canCheckCompatInBackground() && profile.id.equals(launchProfileManager.getSelectedProfileId())) {
					checkCompatPackUpdatesInBackground(true);
				}
			});
		}, "sts2-compat-import-check").start();
	}

	private void checkCompatPackUpdatesInBackground(boolean forceNetwork) {
		if (compatReleaseRepository == null || isFinishing() || isDestroyed()) return;
		if (compatReleaseChecking) {
			if (forceNetwork && !compatReleaseForceInFlight) compatReleaseForceQueued = true;
			return;
		}
		compatReleaseChecking = true;
		compatReleaseForceInFlight = forceNetwork;
		updateCompatReleaseDialog();
		new Thread(() -> {
			CompatPackReleaseIndex checked = null;
			int message = 0;
			try {
				CompatPackReleaseRepository.CachedIndex cached = compatReleaseRepository.getCachedState();
				runOnUiThreadIfActive(() -> publishCompatCache(cached.index, cached.lastCheckedUnix));
				checked = compatReleaseRepository.fetchIndexIfNeeded(forceNetwork);
			} catch (Exception exception) {
				Log.w(TAG, "Official compatibility pack check failed.", exception);
				message = exception instanceof CompatPackReleaseVerifier.TrustUnconfiguredException
					? R.string.compat_online_trust_unconfigured : R.string.compat_online_check_failed;
			}
			CompatPackReleaseIndex result = checked;
			int status = message;
			long checkedAt = status == 0 ? compatReleaseRepository.getLastCheckedUnix() : 0L;
			runOnUiThreadIfActive(() -> {
				compatReleaseChecking = false;
				compatReleaseCheckMessage = status;
				boolean queued = compatReleaseForceQueued;
				compatReleaseForceQueued = false;
				if (status == 0) publishCompatCache(result, checkedAt);
				else updateCompatReleaseDialog();
				if (queued && (canCheckCompatInBackground() || compatReleaseDialog != null)) {
					checkCompatPackUpdatesInBackground(true);
				}
			});
		}, "sts2-compat-release-check").start();
	}

	private void publishCompatCache(CompatPackReleaseIndex index, long checkedAt) {
		boolean changed = compatReleaseIndex != index;
		compatReleaseIndex = index;
		compatReleaseLastChecked = checkedAt;
		updateCompatReleaseDialog();
		if (changed && resumed && currentTabId == R.id.nav_versions && GameVersionManagerPage.isCompatTabSelected()) refreshCurrentScreen();
	}

	private void observeCompatReleaseDialog(Dialog dialog, Runnable render) {
		if (compatReleaseDialog != null && compatReleaseDialog != dialog) compatReleaseDialog.dismiss();
		compatReleaseDialog = dialog;
		renderCompatReleaseDialog = render;
		dialog.setOnDismissListener(unused -> {
			if (compatReleaseDialog == dialog) {
				compatReleaseDialog = null;
				renderCompatReleaseDialog = null;
			}
		});
		updateCompatReleaseDialog();
	}

	private void updateCompatReleaseDialog() {
		if (resumed && compatReleaseDialog != null && compatReleaseDialog.isShowing() && !isFinishing() && !isDestroyed()) {
			renderCompatReleaseDialog.run();
		}
	}

	private void observeCompatLaunchDialog(Dialog dialog, LinearLayout online, CompatLaunchSelection selection, PayloadManager.Status payloadStatus,
			List<CompatPackManager.CompatPack> installedPacks, boolean offlineWarning) {
		class OnlineSection implements Runnable {
			private CompatPackReleaseIndex matchedIndex;
			private CompatPackReleaseRepository.PayloadRecommendation recommendation;

			CompatPackReleaseRepository.PayloadRecommendation currentRecommendation() {
				if (matchedIndex != compatReleaseIndex) {
					matchedIndex = compatReleaseIndex;
					recommendation = matchedIndex == null ? null : compatReleaseRepository.findBestRemoteReleaseForPayload(
						matchedIndex, payloadStatus.manifest, BuildConfig.VERSION_CODE, installedPacks);
				}
				return recommendation;
			}

			@Override public void run() {
				online.removeAllViews();
				online.setVisibility(View.GONE);
				if (online.getParent() == null || compatReleaseRepository == null || !selection.isCurrentScreen()) return;
				GameSettingsActivity owner = GameSettingsActivity.this;
				CompatPackReleaseRepository.PayloadRecommendation remote = currentRecommendation();
				if (remote != null && remote.installable != null) {
					CompatPackRelease release = remote.installable.release;
					CompatPackReleaseTarget target = remote.installable.target;
					String label = offlineWarning
						? getString(R.string.compat_online_switch_launch, release.compatVersion, targetVersions(target))
						: getString(R.string.compat_online_download_launch, release.compatVersion, Formatter.formatFileSize(owner, release.zipSizeBytes));
					MaterialButton download = compatOnlineButton(R.string.compat_online_download, true);
					download.setText(label);
					download.setOnClickListener(v -> installCompatRelease(release, dialog, selection, target));
					online.addView(download);
				}
				if (remote != null && remote.requiresLauncherUpdate != null) {
					CompatPackRelease release = remote.requiresLauncherUpdate.release;
					String message = release.minLauncherVersionCode > BuildConfig.VERSION_CODE
						? getString(R.string.compat_online_launcher_required, release.compatVersion,
							launcherVersionLabel(release.minLauncherVersionCode, release.minLauncherVersionName))
						: getString(R.string.compat_launcher_required_schema);
					MaterialCardView card = ExtraSettingsUi.card(owner);
					LinearLayout body = ExtraSettingsUi.cardContent(owner, card);
					body.addView(ExtraSettingsUi.body(owner, message));
					body.addView(openOfficialLauncherButton());
					ExtraSettingsUi.addSmallSpacing(online, card);
				}
				if (compatReleaseChecking && online.getChildCount() == 0) {
					online.addView(ExtraSettingsUi.caption(owner, getString(R.string.compat_online_checking)));
				}
				if (online.getChildCount() > 0) online.setVisibility(View.VISIBLE);
			}
		}
		OnlineSection section = new OnlineSection();
		observeCompatReleaseDialog(dialog, section);
		if (online.getParent() == null || compatReleaseRepository == null || compatReleaseChecking
				|| compatReleaseCheckMessage == R.string.compat_online_trust_unconfigured || !selection.isCurrentScreen()) return;
		long now = SystemClock.elapsedRealtime();
		if (lastCompatLaunchDialogCheckElapsedMs >= 0L
				&& now - lastCompatLaunchDialogCheckElapsedMs < COMPAT_LAUNCH_DIALOG_CHECK_DEBOUNCE_MS) return;
		CompatPackReleaseRepository.PayloadRecommendation cached = section.currentRecommendation();
		if (cached != null && cached.installable != null) return;
		lastCompatLaunchDialogCheckElapsedMs = now;
		checkCompatPackUpdatesInBackground(true);
	}

	@Override
	public void requestOnlineCompatPacks() {
		if (compatReleaseRepository == null || isFinishing() || isDestroyed() || busy) return;
		BottomSheetDialog dialog = new BottomSheetDialog(this);
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 32));
		content.addView(ExtraSettingsUi.text(this, R.string.compat_online_title, 22, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
		MaterialButton refresh = compatOnlineButton(R.string.compat_online_refresh, false);
		refresh.setOnClickListener(v -> checkCompatPackUpdatesInBackground(true));
		ExtraSettingsUi.addSmallSpacing(content, refresh);
		TextView lastChecked = ExtraSettingsUi.caption(this, "");
		ExtraSettingsUi.addSmallSpacing(content, lastChecked);
		TextView status = ExtraSettingsUi.body(this, "");
		ExtraSettingsUi.addSmallSpacing(content, status);
		LinearLayout releases = ExtraSettingsUi.vertical(this);
		ExtraSettingsUi.addSmallSpacing(content, releases);
		ScrollableBottomSheet.setContent(dialog, content);
		dialog.show();
		observeCompatReleaseDialog(dialog, () -> {
			refresh.setEnabled(!compatReleaseChecking);
			lastChecked.setText(compatReleaseLastChecked <= 0L ? getString(R.string.compat_online_never_checked)
				: getString(R.string.compat_online_last_checked, java.text.DateFormat.getDateTimeInstance().format(new Date(compatReleaseLastChecked * 1000L))));
			int notice = compatReleaseChecking ? R.string.compat_online_checking : compatReleaseCheckMessage;
			boolean empty = compatReleaseIndex == null || compatReleaseIndex.releases.isEmpty();
			if (notice == 0 && empty && compatReleaseLastChecked > 0) notice = R.string.compat_online_empty;
			status.setVisibility(notice == 0 ? View.GONE : View.VISIBLE);
			if (notice != 0) status.setText(notice);
			releases.removeAllViews();
			if (empty) return;
			List<CompatPackManager.CompatPack> packs = compatPackManager.listInstalledPacks();
			PayloadManager.Status payload = payloadManager.getStatus();
			for (CompatPackRelease release : compatReleaseIndex.releases) {
				ExtraSettingsUi.addSmallSpacing(releases, buildCompatReleaseCard(release, packs, payload));
			}
		});
		if (!compatReleaseChecking) checkCompatPackUpdatesInBackground(true);
	}

	private View buildCompatReleaseCard(CompatPackRelease release, List<CompatPackManager.CompatPack> packs, PayloadManager.Status payload) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.text(this, getString(R.string.compat_online_release, release.compatVersion, release.tagName), 16,
			ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.compat_online_published,
			TextUtils.isEmpty(release.publishedUtc) ? getString(R.string.unknown) : release.publishedUtc)));
		Set<String> versions = new LinkedHashSet<>();
		for (CompatPackReleaseTarget target : release.targets) {
			if (target.versions.isEmpty()) versions.add(target.version);
			else versions.addAll(target.versions);
		}
		content.addView(ExtraSettingsUi.body(this, getString(R.string.compat_online_supported, TextUtils.join(" / ", versions))));
		boolean installed = isExactReleaseInstalled(release, packs);
		if (installed) addCompatReleaseBadge(content, getString(R.string.compat_online_installed), ExtraSettingsUi.COLOR_PRIMARY);
		if (payload.ready && compatReleaseRepository.findBestTargetForPayload(release, payload.manifest) != null) {
			addCompatReleaseBadge(content, getString(R.string.compat_online_matches), ExtraSettingsUi.COLOR_PRIMARY);
		}
		if (!TextUtils.isEmpty(release.changelog)) ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, release.changelog));
		if (!release.isInstallable(BuildConfig.VERSION_CODE)) {
			addCompatReleaseBadge(content, launcherRequirement(release.minLauncherVersionCode, release.minLauncherVersionName), ExtraSettingsUi.COLOR_WARNING);
			ExtraSettingsUi.addSmallSpacing(content, openOfficialLauncherButton());
		} else if (!installed) {
			MaterialButton install = compatOnlineButton(R.string.compat_online_download, false);
			install.setOnClickListener(v -> requestOnlineCompatInstall(release));
			ExtraSettingsUi.addSmallSpacing(content, install);
		}
		return card;
	}

	private void addCompatReleaseBadge(LinearLayout content, String text, int color) {
		TextView badge = ExtraSettingsUi.text(this, text, 13, color, android.graphics.Typeface.BOLD);
		int padding = ExtraSettingsUi.dp(this, 8);
		badge.setPadding(padding, padding / 2, padding, padding / 2);
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		background.setCornerRadius(padding);
		badge.setBackground(background);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = padding;
		content.addView(badge, params);
	}

	private boolean isExactReleaseInstalled(CompatPackRelease release, List<CompatPackManager.CompatPack> packs) {
		for (CompatPackReleaseTarget target : release.targets) {
			boolean found = false;
			for (CompatPackManager.CompatPack pack : packs) {
				if (pack.targetId.equals(target.targetId) && isVerifiedInstalledTarget(release, pack)) {
					found = true;
					break;
				}
			}
			if (!found) return false;
		}
		return !release.targets.isEmpty();
	}

	private boolean isVerifiedInstalledTarget(CompatPackRelease release, CompatPackManager.CompatPack pack) {
		org.json.JSONObject source = pack.manifest.optJSONObject("installed_source");
		return pack.ready && release.packId.equals(pack.packId) && source != null
			&& release.zipSha256.equalsIgnoreCase(source.optString("zip_sha256"));
	}

	@Override
	public void requestOnlineCompatInstall(CompatPackRelease release) {
		if (busy || isDestroyed() || isFinishing() || compatReleaseIndex == null || compatReleaseIndex.releases.isEmpty()) return;
		if (!release.isInstallable(BuildConfig.VERSION_CODE)) {
			showCompatLauncherUpdate(launcherRequirement(release.minLauncherVersionCode, release.minLauncherVersionName));
			return;
		}
		boolean historical = !compatReleaseIndex.releases.get(0).tagName.equals(release.tagName);
		for (CompatPackManager.CompatPack pack : compatPackManager.listInstalledPacks()) {
			if (!pack.ready || !release.packId.equals(pack.packId)) continue;
			Integer order = CompatPackVersionComparator.compare(release.compatRevision, release.compatVersion,
				pack.manifest.optInt("compat_revision"), pack.compatVersion);
			if (order == null || order < 0) historical = true;
			break;
		}
		if (historical) {
			new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.compat_online_rollback_title)
				.setMessage(getString(R.string.compat_online_rollback_message, release.compatVersion))
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.compat_online_download, (dialog, which) -> installCompatRelease(release, null, null, null))
				.show();
		} else {
			installCompatRelease(release, null, null, null);
		}
	}

	private void installCompatRelease(CompatPackRelease release, Dialog launchDialog, CompatLaunchSelection selection, CompatPackReleaseTarget target) {
		if (busy || !resumed || isFinishing() || isDestroyed()) return;
		if (selection != null && !isCurrentCompatLaunchDownload(release, target, launchDialog, selection)) return;
		busy = true;
		long foreground = compatForegroundGeneration;
		CancellationSignal cancellation = new CancellationSignal();
		compatDownloadCancellation = cancellation;
		LinearLayout content = ExtraSettingsUi.vertical(this);
		TextView label = ExtraSettingsUi.body(this, "");
		ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		progress.setMax(100);
		progress.setIndeterminate(true);
		content.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		ExtraSettingsUi.addSmallSpacing(content, label);
		label.setText(getString(R.string.compat_online_download_progress, Formatter.formatFileSize(this, 0), Formatter.formatFileSize(this, release.zipSizeBytes)));
		AlertDialog dialog = ExtraSettingsUi.scrollableDialog(this, content)
			.setTitle(getString(R.string.compat_online_download_title, release.compatVersion))
			.setCancelable(false)
			.setNegativeButton(android.R.string.cancel, null)
			.create();
		compatDownloadDialog = dialog;
		dialog.show();
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
			label.setText(R.string.compat_online_cancelling);
			v.setEnabled(false);
			cancellation.cancel();
		});
		new Thread(() -> {
			try {
				int[] lastPercent = {-1};
				compatReleaseRepository.downloadAndInstallRelease(release, (downloaded, total) -> {
					if (total <= 0) return;
					int percent = (int) Math.min(100, downloaded * 100L / total);
					if (percent == lastPercent[0]) return;
					lastPercent[0] = percent;
					runOnUiThreadIfActive(() -> {
						if (compatDownloadDialog != dialog || cancellation.isCanceled()) return;
						progress.setIndeterminate(percent == 100);
						progress.setProgress(percent);
						label.setText(percent == 100 ? getString(R.string.compat_online_installing)
							: getString(R.string.compat_online_download_progress, Formatter.formatFileSize(this, downloaded), Formatter.formatFileSize(this, total)));
					});
				}, cancellation);
				runOnUiThreadIfActive(() -> finishCompatDownload(dialog, release, launchDialog, selection, target, foreground, cancellation.isCanceled(), null));
			} catch (Exception exception) {
				boolean cancelled = cancellation.isCanceled() || exception instanceof OperationCanceledException;
				if (!cancelled) Log.w(TAG, "Official compatibility pack installation failed.", exception);
				runOnUiThreadIfActive(() -> finishCompatDownload(dialog, release, launchDialog, selection, target, foreground, cancelled || cancellation.isCanceled(), exception));
			}
		}, "sts2-compat-release-install").start();
	}

	private void finishCompatDownload(AlertDialog progress, CompatPackRelease release, Dialog launchDialog, CompatLaunchSelection selection,
			CompatPackReleaseTarget target, long foreground, boolean cancelled, Exception failure) {
		if (compatDownloadDialog != progress) return;
		compatDownloadCancellation = null;
		compatDownloadDialog = null;
		busy = false;
		progress.dismiss();
		if (!resumed || foreground != compatForegroundGeneration) {
			if (failure == null && launchDialog != null) launchDialog.dismiss();
			return;
		}
		if (failure != null) {
			if (cancelled) showMessage(getString(R.string.compat_online_install_cancelled));
			else if (failure instanceof CompatPackManager.LauncherUpdateRequiredException) showError(failure);
			else new MaterialAlertDialogBuilder(this)
				.setMessage(R.string.compat_online_install_failed)
				.setPositiveButton(android.R.string.ok, null)
				.show();
			return;
		}
		if (selection != null && !cancelled) {
			LaunchProfileManager.LaunchProfile current = foreground == compatForegroundGeneration && launchDialog.isShowing()
				? selection.current(release, target) : null;
			CompatPackManager.CompatPack installed = current == null ? null : findVerifiedLaunchTarget(release, target, current);
			if (installed != null) {
				try {
					if (bindCompatAndLaunch(launchDialog, selection, installed, release, target)) return;
				} catch (Exception exception) {
					showError(exception);
					return;
				}
			}
			launchDialog.dismiss();
			showMessage(getString(R.string.compat_online_selection_changed));
		} else {
			if (launchDialog != null) launchDialog.dismiss();
			showMessage(getString(R.string.compat_online_installed_done, release.compatVersion));
		}
		refreshCurrentScreen();
		updateCompatReleaseDialog();
	}

	private boolean isCurrentCompatLaunchDownload(CompatPackRelease release, CompatPackReleaseTarget target,
			Dialog launchDialog, CompatLaunchSelection selection) {
		LaunchProfileManager.LaunchProfile current = selection.current(null, null);
		if (current == null || !launchDialog.isShowing()) return false;
		List<CompatPackManager.CompatPack> packs = compatPackManager.listInstalledPacks();
		CompatPackManager.CompatPack local = compatPackManager.findBestMatch(current.payload.manifest, packs);
		CompatPackReleaseRepository.RemoteMatch match = compatReleaseRepository.findBestRemoteReleaseForPayload(
			compatReleaseIndex, current.payload.manifest, BuildConfig.VERSION_CODE, packs).installable;
		if ((local == null || compatPackManager.isOfflineBootstrapPack(local)) && match != null
			&& release.tagName.equals(match.release.tagName) && release.zipSha256.equals(match.release.zipSha256)
			&& target.targetId.equals(match.target.targetId)) return true;
		launchDialog.dismiss();
		if (selection.current(null, null) != null) launchGame();
		return false;
	}

	private CompatPackManager.CompatPack findVerifiedLaunchTarget(CompatPackRelease release, CompatPackReleaseTarget target, LaunchProfileManager.LaunchProfile profile) {
		for (CompatPackManager.CompatPack pack : compatPackManager.listInstalledPacks()) {
			if (pack.targetId.equals(target.targetId) && isVerifiedInstalledTarget(release, pack)
				&& compatPackManager.isPackCompatibleWithPayload(pack, profile.payload.manifest)) return pack;
		}
		return null;
	}

	private String targetVersions(CompatPackReleaseTarget target) {
		if (!target.versions.isEmpty()) return TextUtils.join(" / ", target.versions);
		return TextUtils.isEmpty(target.version) ? target.displayName : target.version;
	}

	private String launcherRequirement(int minLauncherCode, String minLauncherName) {
		if (minLauncherCode <= BuildConfig.VERSION_CODE) return getString(R.string.compat_launcher_required_schema);
		return getString(R.string.compat_launcher_required_version, launcherVersionLabel(minLauncherCode, minLauncherName));
	}

	private String launcherVersionLabel(int minLauncherCode, String minLauncherName) {
		return TextUtils.isEmpty(minLauncherName) ? getString(R.string.compat_online_launcher_code, minLauncherCode) : minLauncherName;
	}

	private MaterialButton compatOnlineButton(int label, boolean filled) {
		MaterialButton button = filled ? ExtraSettingsUi.filledButton(this, label, R.drawable.ic_download_24) : ExtraSettingsUi.outlineButton(this, label, 0);
		button.setSingleLine(false);
		button.setEllipsize(null);
		button.setAllCaps(false);
		button.setMinHeight(ExtraSettingsUi.dp(this, 48));
		return button;
	}

	private MaterialButton openOfficialLauncherButton() {
		MaterialButton button = compatOnlineButton(R.string.compat_online_open_launcher, false);
		button.setOnClickListener(v -> openUrl(ExtraSettingsUpdateChecker.RELEASES_URL));
		return button;
	}

	private void showCompatLauncherUpdate(String requirement) {
		if (isFinishing() || isDestroyed()) return;
		new MaterialAlertDialogBuilder(this)
			.setMessage(requirement)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.compat_online_open_launcher, (dialog, which) -> openUrl(ExtraSettingsUpdateChecker.RELEASES_URL))
			.show();
	}

	private void prepareAndStartGameAfterOptionalSteamCloudPull() {
		if (SteamSettings.shouldPullBeforeLaunch(this) && SteamAuthStore.readAuthMaterial(this) != null) {
			runSteamCloudPullThenMaybeWebDavPull();
			return;
		}
		runWebDavPullThenLaunchIfEnabled();
	}

	private void runSteamCloudPullThenMaybeWebDavPull() {
		if (busy) {
			return;
		}
		busy = true;
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.steam_operation_progress_title), getString(R.string.steam_cloud_auto_pull_busy));
		progressDialog.show();
		new Thread(() -> {
			try {
				createPreLaunchLocalSaveSnapshotIfNeeded();
				String result = new Sts2SteamCloudSyncManager(this).pullAll((percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					showMessage(result);
					runWebDavPullThenLaunchIfEnabled();
				});
			} catch (Exception exception) {
				Sts2SteamCloudSyncManager.CloudConflictException conflict = findSteamCloudConflict(exception);
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (conflict != null) {
						showSteamCloudLaunchConflictDialog(conflict);
					} else {
						showSteamCloudLaunchFailureDialog(exception);
					}
				});
			}
		}, "sts2-steam-cloud-prelaunch").start();
	}

	private void runWebDavPullThenLaunchIfEnabled() {
		if (!WebDavSettings.shouldPullBeforeLaunch(this) || !WebDavSettings.isConfigured(this)) {
			prepareAndStartGame();
			return;
		}
		if (busy) {
			return;
		}
		busy = true;
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.webdav_operation_progress_title), getString(R.string.webdav_cloud_auto_pull_busy));
		progressDialog.show();
		new Thread(() -> {
			try {
				createPreLaunchLocalSaveSnapshotIfNeeded();
				String result = new WebDavSyncManager(this).pullAll((percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					showMessage(result);
					prepareAndStartGame();
				});
			} catch (Exception exception) {
				WebDavSyncManager.CloudConflictException conflict = findWebDavCloudConflict(exception);
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (conflict != null) {
						showWebDavCloudLaunchConflictDialog(conflict);
					} else {
						showWebDavCloudLaunchFailureDialog(exception);
					}
				});
			}
		}, "sts2-webdav-cloud-prelaunch").start();
	}

	private void createPreLaunchLocalSaveSnapshotIfNeeded() {
		synchronized (this) {
			if (preLaunchLocalSnapshotCreated) {
				return;
			}
			preLaunchLocalSnapshotCreated = true;
		}
		try {
			new LocalSaveSnapshotManager(this).createAutomaticSnapshot("before-launch");
		} catch (Exception exception) {
			Log.w(TAG, "Unable to create pre-launch local save snapshot.", exception);
		}
	}

	private void showSteamCloudLaunchConflictDialog(Sts2SteamCloudSyncManager.CloudConflictException conflict) {
		String message = getString(
			R.string.steam_cloud_conflict_message,
			conflict.getConflictCount(),
			conflict.getConflictSummary(8)
		);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_cloud_conflict_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.steam_cloud_conflict_keep_cloud, (dialog, which) -> runSteamCloudOperationThenLaunch(getString(R.string.steam_cloud_auto_pull_busy), (manager, progressListener) -> manager.pullAll(true, progressListener)))
			.setPositiveButton(R.string.steam_cloud_conflict_keep_local, (dialog, which) -> runSteamCloudOperationThenLaunch(getString(R.string.steam_cloud_auto_push_busy), (manager, progressListener) -> manager.pushLocalChanges(true, progressListener)))
			.show();
	}

	private void runSteamCloudOperationThenLaunch(String busyMessage, SteamCloudOperation operation) {
		if (busy) {
			return;
		}
		busy = true;
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.steam_operation_progress_title), busyMessage);
		progressDialog.show();
		new Thread(() -> {
			try {
				String result = operation.run(new Sts2SteamCloudSyncManager(this), (percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					showMessage(result);
					runWebDavPullThenLaunchIfEnabled();
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					showSteamCloudLaunchFailureDialog(exception);
				});
			}
		}, "sts2-steam-cloud-launch-resolution").start();
	}

	private void showSteamCloudLaunchFailureDialog(Exception exception) {
		String message = getString(R.string.steam_cloud_launch_sync_failed_message, exception.getMessage() == null ? exception.toString() : exception.getMessage());
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_cloud_launch_sync_failed_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.steam_cloud_open_center, (dialog, which) -> openSteamAccount())
			.setPositiveButton(R.string.launch_anyway, (dialog, which) -> prepareAndStartGame())
			.show();
	}

	private void showWebDavCloudLaunchConflictDialog(WebDavSyncManager.CloudConflictException conflict) {
		String message = getString(
			R.string.webdav_cloud_conflict_message,
			conflict.getConflictCount(),
			conflict.getConflictSummary(8)
		);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_cloud_conflict_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.webdav_cloud_conflict_keep_cloud, (dialog, which) -> runWebDavCloudOperationThenLaunch(getString(R.string.webdav_cloud_auto_pull_busy), (manager, progressListener) -> manager.pullAll(true, progressListener)))
			.setPositiveButton(R.string.webdav_cloud_conflict_keep_local, (dialog, which) -> runWebDavCloudOperationThenLaunch(getString(R.string.webdav_cloud_auto_push_busy), (manager, progressListener) -> manager.pushLocalChanges(true, progressListener)))
			.show();
	}

	private void runWebDavCloudOperationThenLaunch(String busyMessage, WebDavCloudOperation operation) {
		if (busy) {
			return;
		}
		busy = true;
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.webdav_operation_progress_title), busyMessage);
		progressDialog.show();
		new Thread(() -> {
			try {
				String result = operation.run(new WebDavSyncManager(this), (percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					showMessage(result);
					prepareAndStartGame();
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					showWebDavCloudLaunchFailureDialog(exception);
				});
			}
		}, "sts2-webdav-cloud-launch-resolution").start();
	}

	private void showWebDavCloudLaunchFailureDialog(Exception exception) {
		String message = getString(R.string.webdav_cloud_launch_sync_failed_message, exception.getMessage() == null ? exception.toString() : exception.getMessage());
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_cloud_launch_sync_failed_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.webdav_open_center, (dialog, which) -> openWebDavCloud())
			.setPositiveButton(R.string.launch_anyway, (dialog, which) -> prepareAndStartGame())
			.show();
	}

	private void maybeRunCleanExitSteamCloudPush() {
		if (busy || cleanExitMaintenanceChecked) {
			return;
		}
		// Check the small, single-use marker before opening either credential store.
		cleanExitMaintenanceChecked = true;
		if (SteamCleanExitTracker.consumeIfRecent(this)) runCleanExitSaveMaintenance();
	}

	private void runCleanExitSaveMaintenance() {
		if (busy) {
			return;
		}
		busy = true;
		String initialMessage = getString(R.string.status_busy_create_local_save_snapshot);
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.cloud_operation_progress_title), initialMessage);
		progressDialog.show();
		new Thread(() -> {
			List<String> results = new ArrayList<>();
			try {
				boolean shouldPushSteam = SteamSettings.shouldPushAfterCleanExit(this) && SteamAuthStore.readAuthMaterial(this) != null;
				WebDavSettings.Config webDav = WebDavSettings.readConfig(this);
				boolean shouldPushWebDav = webDav.isConfigured() && WebDavSettings.CLOUD_MODE_FULL_AUTO.equals(webDav.cloudMode);
				runOnUiThreadIfActive(() -> progressDialog.setProgress(0, getString(R.string.status_busy_create_local_save_snapshot)));
				new LocalSaveSnapshotManager(this).createAutomaticSnapshot("clean-exit");
				if (shouldPushSteam) {
					String result = new Sts2SteamCloudSyncManager(this).pushLocalChanges(false, (percent, message) -> runOnUiThreadIfActive(() -> progressDialog.setProgress(percent, message)));
					results.add(result);
				}
				if (shouldPushWebDav) {
					String result = new WebDavSyncManager(this).pushLocalChanges(false, (percent, message) -> runOnUiThreadIfActive(() -> progressDialog.setProgress(percent, message)));
					results.add(result);
				}
				runOnUiThreadIfActive(() -> {
					busy = false;
					progressDialog.dismiss();
					if (!results.isEmpty()) {
						showMessage(String.join("\n", results));
					}
				});
			} catch (Exception exception) {
				runOnUiThreadIfActive(() -> {
					busy = false;
					progressDialog.dismiss();
					showError(exception);
				});
			}
		}, "sts2-clean-exit-save-maintenance").start();
	}

	private void runOnUiThreadIfActive(Runnable action) {
		runOnUiThread(() -> {
			if (!isFinishing() && !isDestroyed()) {
				action.run();
			}
		});
	}

	private void runSteamCloudOperationWithDialog(String busyMessage, boolean refreshAfterSuccess, SteamCloudOperation operation) {
		if (busy) {
			return;
		}
		busy = true;
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.steam_operation_progress_title), busyMessage);
		progressDialog.show();
		new Thread(() -> {
			try {
				String result = operation.run(new Sts2SteamCloudSyncManager(this), (percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (refreshAfterSuccess) {
						refreshCurrentScreen();
					}
					showMessage(result);
				});
			} catch (Exception exception) {
				Sts2SteamCloudSyncManager.CloudConflictException conflict = findSteamCloudConflict(exception);
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (conflict != null) {
						showSteamCloudConflictDialog(conflict);
					} else {
						showError(exception);
					}
				});
			}
		}, "sts2-steam-cloud-operation").start();
	}

	private void showSteamCloudConflictDialog(Sts2SteamCloudSyncManager.CloudConflictException conflict) {
		String message = getString(
			R.string.steam_cloud_conflict_message,
			conflict.getConflictCount(),
			conflict.getConflictSummary(8)
		);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_cloud_conflict_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.steam_cloud_conflict_keep_cloud, (dialog, which) -> runSteamCloudOperationWithDialog(getString(R.string.steam_status_cloud_busy), true, (manager, progressListener) -> manager.pullAll(true, progressListener)))
			.setPositiveButton(R.string.steam_cloud_conflict_keep_local, (dialog, which) -> runSteamCloudOperationWithDialog(getString(R.string.steam_status_cloud_busy), true, (manager, progressListener) -> manager.pushLocalChanges(true, progressListener)))
			.show();
	}

	private Sts2SteamCloudSyncManager.CloudConflictException findSteamCloudConflict(Throwable exception) {
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

	private void runWebDavCloudOperationWithDialog(String busyMessage, boolean refreshAfterSuccess, WebDavCloudOperation operation) {
		if (busy) {
			return;
		}
		busy = true;
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.webdav_operation_progress_title), busyMessage);
		progressDialog.show();
		new Thread(() -> {
			try {
				String result = operation.run(new WebDavSyncManager(this), (percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (refreshAfterSuccess) {
						refreshCurrentScreen();
					}
					showMessage(result);
				});
			} catch (Exception exception) {
				WebDavSyncManager.CloudConflictException conflict = findWebDavCloudConflict(exception);
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (conflict != null) {
						showWebDavCloudConflictDialog(conflict);
					} else {
						showError(exception);
					}
				});
			}
		}, "sts2-webdav-cloud-operation").start();
	}

	private void showWebDavCloudConflictDialog(WebDavSyncManager.CloudConflictException conflict) {
		String message = getString(
			R.string.webdav_cloud_conflict_message,
			conflict.getConflictCount(),
			conflict.getConflictSummary(8)
		);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.webdav_cloud_conflict_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.webdav_cloud_conflict_keep_cloud, (dialog, which) -> runWebDavCloudOperationWithDialog(getString(R.string.webdav_status_cloud_busy), true, (manager, progressListener) -> manager.pullAll(true, progressListener)))
			.setPositiveButton(R.string.webdav_cloud_conflict_keep_local, (dialog, which) -> runWebDavCloudOperationWithDialog(getString(R.string.webdav_status_cloud_busy), true, (manager, progressListener) -> manager.pushLocalChanges(true, progressListener)))
			.show();
	}

	private WebDavSyncManager.CloudConflictException findWebDavCloudConflict(Throwable exception) {
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

	private void prepareAndStartGame() {
		if (busy) {
			return;
		}
		busy = true;
		showMessage(getString(R.string.status_busy_prepare_launch));
		GameLaunchPreparationManager preparation = new GameLaunchPreparationManager(this);
		cancelLaunchPreparation = ModInstallReadiness.run(this, () -> {
			createPreLaunchLocalSaveSnapshotIfNeeded();
			preparation.prepareForLaunch();
		}, () -> Snackbar.make(findViewById(android.R.id.content), R.string.mod_install_waiting, Snackbar.LENGTH_INDEFINITE)
			.setAction(android.R.string.cancel, v -> {
				if (cancelLaunchPreparation != null) cancelLaunchPreparation.run();
				cancelLaunchPreparation = null;
				busy = false;
			}).show(), () -> {
			cancelLaunchPreparation = null;
			busy = false;
			startActivity(preparation.preparedIntent(GodotApp.createLaunchIntent(this, true)));
			finish();
		}, exception -> {
			cancelLaunchPreparation = null;
			busy = false;
			showError(exception);
		});
	}

	private void installBundledCompatPacksInBackground(boolean showResult) {
		if (showResult) {
			runAsyncOperation(getString(R.string.status_busy_install_bundled_compat), () -> {
				CompatPackManager.BundledInstallResult result = compatPackManager.installBundledCompatPacksWithResult();
				String installed = getString(R.string.status_install_bundled_compat_done, result.changedCount);
				if (!result.preservedNewerOrExternal) return installed;
				String preserved = getString(R.string.compat_bundled_preserved);
				return result.changedCount == 0 ? preserved : installed + "\n" + preserved;
			});
			return;
		}
		new Thread(() -> {
			try {
				compatPackManager.installBundledCompatPacks();
				runOnUiThreadIfActive(() -> finishCompatBootstrap(true));
			} catch (Exception exception) {
				Log.w(TAG, "Unable to install bundled compatibility packs.", exception);
				runOnUiThreadIfActive(() -> finishCompatBootstrap(false));
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
			gear.setImageDrawable(MaterialSymbols.drawable(GameSettingsActivity.this, "settings", ExtraSettingsUi.COLOR_PRIMARY, 56));
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

			dialog = ExtraSettingsUi.scrollableDialog(GameSettingsActivity.this, content).create();
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
		requestUpdateCheck(false);
	}

	@Override
	public void requestManualUpdateCheck() {
		requestUpdateCheck(true);
	}

	private void requestUpdateCheck(boolean manualCheck) {
		new Thread(() -> {
			try {
				ExtraSettingsUpdateChecker.UpdateCheckResult result = ExtraSettingsUpdateChecker.check(this);
				if (result.updateInfo != null) {
					runOnUiThread(() -> showUpdateDialog(result.updateInfo));
				} else if (manualCheck && result.alreadyLatest) {
					runOnUiThread(() -> showUpdateCheckAlreadyLatestMessage());
				} else if (manualCheck && result.unavailableReason != null) {
					Log.w(TAG, "Manual update check unavailable: " + result.unavailableReason);
					runOnUiThread(() -> showUpdateCheckFailedDialog(result.unavailableReason));
				}
			} catch (Exception exception) {
				if (manualCheck) {
					Log.w(TAG, "Manual update check failed.", exception);
					runOnUiThread(() -> showUpdateCheckFailedDialog(exception));
				} else {
					Log.w(TAG, "Update check failed silently.", exception);
				}
			}
		}, "sts2-update-check").start();
	}

	private void showUpdateCheckAlreadyLatestMessage() {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		showSnackbar(getString(R.string.update_check_already_latest_message, BuildConfig.VERSION_NAME), Snackbar.LENGTH_SHORT);
	}

	private void showUpdateCheckFailedDialog(Exception exception) {
		showUpdateCheckFailedDialog(exception.getMessage() == null ? exception.toString() : exception.getMessage());
	}

	private void showUpdateCheckFailedDialog(String detail) {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.update_check_failed_title)
			.setMessage(getString(R.string.update_check_failed_message, detail))
			.setPositiveButton(android.R.string.ok, null)
			.show();
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
			.setNeutralButton(R.string.update_bilibili, (dialog, which) -> openUrl(UPDATE_BILIBILI_DYNAMIC_URL))
			.setPositiveButton(R.string.update_download, (dialog, which) -> openUrl(updateInfo.releaseUrl))
			.show();
	}

	@Override
	public void openModsTab() {
		selectNavigationItem(R.id.nav_mods);
	}

	@Override
	public void openSettingsTab() {
		selectNavigationItem(R.id.nav_settings);
	}

	@Override
	public void openSaveSettingsTab() {
		SettingsPage.selectSaveSegment();
		openSettingsTab();
	}

	@Override
	public void openVersionsTab() {
		selectNavigationItem(R.id.nav_versions);
	}

	@Override
	public void refreshCurrentScreen() {
		if (isFinishing() || isDestroyed()) return;
		if (currentTabId == R.id.nav_game && homePage != null) {
			homePage.refresh();
		} else if (contentFrame != null) {
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
		ScrollView scrollView = findScrollView(contentFrame);
		if (scrollView == null) return;
		scrollView.post(() -> {
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
		if (exception instanceof ModInstallStore.BusyException) {
			showMessage(getString(R.string.mod_install_busy));
			return;
		}
		if (exception instanceof CompatPackManager.LauncherUpdateRequiredException required) {
			showCompatLauncherUpdate(launcherRequirement(required.minLauncherVersionCode, required.minLauncherVersionName));
			return;
		}
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

	private enum CompatRecommendationKind {
		BUNDLED,
		INSTALLED,
		OFFLINE
	}

	private static final class CompatRecommendation {
		final CompatPackManager.CompatPack pack;
		final CompatRecommendationKind kind;

		CompatRecommendation(CompatPackManager.CompatPack pack, CompatRecommendationKind kind) {
			this.pack = pack;
			this.kind = kind;
		}
	}

	private interface SteamCloudOperation {
		String run(Sts2SteamCloudSyncManager manager, Sts2SteamCloudSyncManager.ProgressListener progressListener) throws Exception;
	}

	private interface WebDavCloudOperation {
		String run(WebDavSyncManager manager, WebDavSyncManager.ProgressListener progressListener) throws Exception;
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
		if (!directoriesReady) {
			finish();
			return;
		}
		if (!ExtraSettingsPreferences.isFirstRunSetupCompleted(this)) {
			new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.welcome_exit_title)
				.setMessage(R.string.welcome_exit_message)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.welcome_enter_settings, (dialog, which) -> {
					ExtraSettingsPreferences.setFirstRunSetupCompleted(this, true);
					maybeCheckCompatOnStartup();
					showMainShell();
				})
				.show();
			return;
		}
		super.onBackPressed();
	}
}
