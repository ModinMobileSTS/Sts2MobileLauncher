package com.godot.game;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import com.godot.game.steam.auth.SteamAuthStore;
import com.godot.game.steam.cloud.SteamCleanExitTracker;
import com.godot.game.steam.cloud.Sts2SteamCloudSyncManager;
import com.godot.game.steam.core.SteamSettings;
import com.godot.game.webdav.WebDavSettings;
import com.godot.game.webdav.WebDavSyncManager;

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
	private LaunchProfileManager launchProfileManager;
	private FrameLayout contentFrame;
	private BottomNavigationView bottomNavigationView;
	private NavigationRailView navigationRailView;
	private boolean busy;
	private boolean launchUpdateCheckRequested;
	private boolean bundledPayloadAutoExtractRequested;
	private boolean bundledCompatPackBootstrapFinished;
	private boolean pendingLauncherDirectLaunch;
	private boolean preLaunchLocalSnapshotCreated;
	private boolean cleanExitMaintenanceChecked;
	private int currentTabId = R.id.nav_game;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		repository = new ExtraSettingsRepository(this);
		payloadManager = new PayloadManager(this);
		compatPackManager = new CompatPackManager(this);
		gameBodyVersionManager = new GameBodyVersionManager(this);
		launchProfileManager = new LaunchProfileManager(this);
		launchProfileManager.bootstrapIfNeeded();
		repository.ensureAppDirectories();
		installBundledCompatPacksInBackground(false);
		if (!ExtraSettingsPreferences.isFirstRunSetupCompleted(this)) {
			showWelcome();
		} else {
			showMainShell();
			maybeLaunchGameFromLauncherIntent(savedInstanceState);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (ExtraSettingsPreferences.isFirstRunSetupCompleted(this) && contentFrame != null) {
			refreshCurrentScreen();
			maybeRunCleanExitSteamCloudPush();
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
		boolean wideLayout = ExtraSettingsUi.isWideLayout(this);
		LinearLayout shell = new LinearLayout(this);
		shell.setOrientation(wideLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
		shell.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		contentFrame = new FrameLayout(this);
		bottomNavigationView = null;
		navigationRailView = null;
		if (wideLayout) {
			navigationRailView = new NavigationRailView(this);
			navigationRailView.inflateMenu(R.menu.menu_extra_settings_nav);
			configureNavigationBar(navigationRailView, ExtraSettingsUi.dp(this, 72));
			navigationRailView.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
			navigationRailView.setItemMinimumHeight(ExtraSettingsUi.dp(this, 64));
			navigationRailView.setPadding(0, ExtraSettingsUi.dp(this, 12), 0, ExtraSettingsUi.dp(this, 12));
			shell.addView(navigationRailView, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 96), ViewGroup.LayoutParams.MATCH_PARENT));
			shell.addView(contentFrame, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
		} else {
			shell.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
			bottomNavigationView = new BottomNavigationView(this);
			bottomNavigationView.inflateMenu(R.menu.menu_extra_settings_nav);
			configureNavigationBar(bottomNavigationView, ExtraSettingsUi.dp(this, 64));
			bottomNavigationView.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
			shell.addView(bottomNavigationView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}

		setContentView(shell);
		int savedTab = ExtraSettingsPreferences.getLastSelectedMainTab(this, R.id.nav_game);
		if (savedTab != R.id.nav_game && savedTab != R.id.nav_mods && savedTab != R.id.nav_versions && savedTab != R.id.nav_settings && savedTab != R.id.nav_about) {
			savedTab = R.id.nav_game;
		}
		selectNavigationItem(savedTab);
		openTab(savedTab);
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
		if (bottomNavigationView != null) {
			bottomNavigationView.setSelectedItemId(itemId);
		}
		if (navigationRailView != null) {
			navigationRailView.setSelectedItemId(itemId);
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
		if (bundledPayloadAutoExtractRequested || busy || !payloadManager.hasBundledPayload() || payloadManager.getStatus().ready) {
			return;
		}
		bundledPayloadAutoExtractRequested = true;
		requestExtractBundledPayload();
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
		runPendingLauncherDirectLaunch();
	}

	private void runPendingLauncherDirectLaunch() {
		if (!pendingLauncherDirectLaunch || !bundledCompatPackBootstrapFinished || contentFrame == null) {
			return;
		}
		contentFrame.post(() -> {
			if (!pendingLauncherDirectLaunch || isFinishing() || isDestroyed()) {
				return;
			}
			if (busy) {
				contentFrame.postDelayed(this::runPendingLauncherDirectLaunch, 500);
				return;
			}
			pendingLauncherDirectLaunch = false;
			launchGame();
		});
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
			CompatPackManager.CompatPack selectedCompatPack = compatPackManager.getSelectedPack();
			if (selectedCompatPack == null) {
				showMessage(getString(R.string.launch_requires_compat_pack));
				return;
			}
			if (!compatPackManager.isPackCompatibleWithPayload(selectedCompatPack, payloadStatus.manifest)) {
				showCompatMismatchDialog(payloadStatus, selectedCompatPack);
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
			final String[] selectedCompatPackId = new String[] { profile == null ? findBestCompatPackIdForPayload(selectedPayload[0]) : profile.compatPackId };
			final MaterialButton[] compatButtonRef = new MaterialButton[1];

			BottomSheetDialog dialog = new BottomSheetDialog(this);
			LinearLayout content = ExtraSettingsUi.vertical(this);
			content.setPadding(ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 32));
			GradientDrawable background = new GradientDrawable();
			background.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
			float radius = ExtraSettingsUi.dp(this, 28);
			background.setCornerRadii(new float[] { radius, radius, radius, radius, 0, 0, 0, 0 });
			content.setBackground(background);

			View handle = new View(this);
			GradientDrawable handleBackground = new GradientDrawable();
			handleBackground.setColor(Color.argb(104, 202, 196, 208));
			handleBackground.setCornerRadius(ExtraSettingsUi.dp(this, 2));
			handle.setBackground(handleBackground);
			LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 32), ExtraSettingsUi.dp(this, 4));
			handleParams.gravity = Gravity.CENTER_HORIZONTAL;
			handleParams.bottomMargin = ExtraSettingsUi.dp(this, 24);
			content.addView(handle, handleParams);

			content.addView(ExtraSettingsUi.text(this, profile == null ? R.string.create_launch_profile_title : R.string.edit_launch_profile_title, 22, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));

			EditText nameInput = new EditText(this);
			nameInput.setSingleLine(true);
			nameInput.setHint(R.string.launch_profile_name_hint);
			nameInput.setText(profile == null ? selectedPayload[0].label : profile.displayName);
			nameInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			nameInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
			ExtraSettingsUi.addSmallSpacing(content, nameInput);

			MaterialButton selectPayload = ExtraSettingsUi.outlineButton(this, R.string.launch_profile_select_payload_title, R.drawable.ic_folder_24);
			selectPayload.setText(getString(R.string.launch_profile_select_payload_button, launchProfilePayloadLabel(profile, selectedPayload[0])));
			selectPayload.setOnClickListener(v -> showLaunchProfilePayloadPicker(selectedPayload[0], picked -> {
				selectedPayload[0] = picked;
				selectedCompatPackId[0] = findBestCompatPackIdForPayload(picked);
				selectPayload.setText(getString(R.string.launch_profile_select_payload_button, picked.label));
				if (compatButtonRef[0] != null) {
					setLaunchProfileCompatButtonText(compatButtonRef[0], selectedCompatPackId[0]);
				}
				String currentName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
				if (currentName.isEmpty()) {
					nameInput.setText(picked.label);
				}
			}));
			ExtraSettingsUi.addSmallSpacing(content, selectPayload);

			MaterialButton selectCompat = ExtraSettingsUi.outlineButton(this, R.string.launch_profile_select_compat_title, R.drawable.ic_extension_24);
			compatButtonRef[0] = selectCompat;
			setLaunchProfileCompatButtonText(selectCompat, selectedCompatPackId[0]);
			selectCompat.setOnClickListener(v -> showLaunchProfileCompatPicker(selectedCompatPackId[0], selectedPayload[0], picked -> {
				selectedCompatPackId[0] = picked;
				setLaunchProfileCompatButtonText(selectCompat, selectedCompatPackId[0]);
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
						launchProfileManager.createProfile(selectedPayload[0].id, name, saveMode, modsMode, selectedCompatPackId[0], true);
						repository.ensureAppDirectories();
						return getString(R.string.status_create_launch_profile_done);
					}
					String updatedPayloadId = selectedPayload[0] == null ? profile.payloadId : selectedPayload[0].id;
					launchProfileManager.updateProfile(profile.id, updatedPayloadId, name, saveMode, modsMode, selectedCompatPackId[0]);
					repository.ensureAppDirectories();
					return getString(R.string.status_update_launch_profile_done);
				});
			});
			buttons.addView(cancel);
			LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			okParams.setMarginStart(ExtraSettingsUi.dp(this, 10));
			buttons.addView(ok, okParams);
			ExtraSettingsUi.addSmallSpacing(content, buttons);

			dialog.setContentView(content);
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
		try {
			CompatPackManager.CompatPack pack = payload == null ? null : compatPackManager.findBestMatch(payload.manifest);
			return pack == null ? "" : pack.packId;
		} catch (Exception ignored) {
			return "";
		}
	}

	private void setLaunchProfileCompatButtonText(MaterialButton button, String compatPackId) {
		button.setText(getString(R.string.launch_profile_select_compat_button, launchProfileCompatLabel(compatPackId)));
	}

	private String launchProfileCompatLabel(String compatPackId) {
		if (TextUtils.isEmpty(compatPackId)) {
			return getString(R.string.launch_profile_select_compat_none);
		}
		CompatPackManager.CompatPack pack = findCompatPackById(compatPackManager.listInstalledPacks(), compatPackId);
		if (pack == null) {
			return getString(R.string.launch_profile_compat_missing_format, compatPackId);
		}
		return getString(R.string.version_manager_selected_compat_format, pack.displayName, pack.targetLabel());
	}

	private CompatPackManager.CompatPack findCompatPackById(List<CompatPackManager.CompatPack> packs, String packId) {
		if (TextUtils.isEmpty(packId) || packs == null) {
			return null;
		}
		for (CompatPackManager.CompatPack pack : packs) {
			if (pack.packId.equals(packId)) {
				return pack;
			}
		}
		return null;
	}

	private void showLaunchProfileCompatPicker(String currentCompatPackId, LaunchProfileManager.GamePayload selectedPayload, LaunchProfileCompatPickerCallback callback) {
		try {
			List<CompatPackManager.CompatPack> packs = compatPackManager.listInstalledPacks();
			List<String> ids = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			ids.add("");
			labels.add(getString(R.string.launch_profile_select_compat_none));
			int checked = 0;
			for (CompatPackManager.CompatPack pack : packs) {
				ids.add(pack.packId);
				String label = getString(R.string.version_manager_selected_compat_format, pack.displayName, pack.targetLabel());
				if (selectedPayload != null && compatPackManager.isPackCompatibleWithPayload(pack, selectedPayload.manifest)) {
					label = label + " ✓";
				}
				labels.add(label);
				if (pack.packId.equals(currentCompatPackId)) {
					checked = ids.size() - 1;
				}
			}
			new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.launch_profile_select_compat_title)
				.setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
					callback.onCompatPicked(ids.get(which));
					dialog.dismiss();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private interface LaunchProfileCompatPickerCallback {
		void onCompatPicked(String compatPackId);
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
	public void openSteamAccount() {
		try {
			startActivity(new Intent(this, SteamAccountActivity.class));
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
			if (!uris.isEmpty()) {
				prepareModImportsWithConflictDialogs(uris);
			}
		}
	}

	private void prepareModImportsWithConflictDialogs(List<Uri> uris) {
		if (busy || uris == null || uris.isEmpty()) {
			return;
		}
		busy = true;
		showMessage(getString(R.string.status_busy_import_mod));
		new Thread(() -> {
			List<ExtraSettingsRepository.PreparedModImport> preparedImports = new ArrayList<>();
			try {
				for (Uri uri : uris) {
					preparedImports.add(repository.prepareModImport(uri));
				}
				runOnUiThread(() -> {
					busy = false;
					handlePreparedModImports(preparedImports, 0, 0, "");
				});
			} catch (Exception exception) {
				for (ExtraSettingsRepository.PreparedModImport preparedImport : preparedImports) {
					repository.discardPreparedModImport(preparedImport);
				}
				runOnUiThread(() -> {
					busy = false;
					showError(exception);
				});
			}
		}, "sts2-mod-import-prepare").start();
	}

	private void handlePreparedModImports(List<ExtraSettingsRepository.PreparedModImport> imports, int index, int importedCount, String lastName) {
		if (index >= imports.size()) {
			refreshCurrentScreen();
			if (importedCount == 1 && lastName != null && !lastName.isEmpty()) {
				showMessage(getString(R.string.status_import_mod_done) + " " + lastName);
			} else if (importedCount > 0) {
				showMessage(getString(R.string.status_import_mod_done_count, importedCount));
			} else {
				showMessage(getString(R.string.status_import_mod_cancelled));
			}
			return;
		}
		ExtraSettingsRepository.PreparedModImport preparedImport = imports.get(index);
		List<ExtraSettingsRepository.ModImportConflict> currentConflicts = repository.findCurrentImportConflicts(preparedImport);
		if (currentConflicts.isEmpty()) {
			handlePreparedModImportPathConflicts(imports, index, importedCount, lastName, false, currentConflicts);
			return;
		}
		showModImportConflictDialog(preparedImport, currentConflicts,
			() -> {
				repository.discardPreparedModImport(preparedImport);
				handlePreparedModImports(imports, index + 1, importedCount, lastName);
			},
			() -> handlePreparedModImportPathConflicts(imports, index, importedCount, lastName, true, currentConflicts),
			() -> {
				repository.discardPreparedModImport(preparedImport);
				handlePreparedModImports(imports, index + 1, importedCount, lastName);
			});
	}

	private void handlePreparedModImportPathConflicts(List<ExtraSettingsRepository.PreparedModImport> imports, int index, int importedCount, String lastName, boolean replaceExistingConflicts, List<ExtraSettingsRepository.ModImportConflict> confirmedIdConflicts) {
		ExtraSettingsRepository.PreparedModImport preparedImport = imports.get(index);
		List<ExtraSettingsRepository.ModImportPathConflict> pathConflicts = repository.findCurrentImportPathConflicts(preparedImport, replaceExistingConflicts ? confirmedIdConflicts : new ArrayList<>());
		if (pathConflicts.isEmpty()) {
			commitPreparedModImport(imports, index, importedCount, lastName, replaceExistingConflicts, false);
			return;
		}
		showModImportPathConflictDialog(preparedImport, pathConflicts,
			() -> {
				repository.discardPreparedModImport(preparedImport);
				handlePreparedModImports(imports, index + 1, importedCount, lastName);
			},
			() -> commitPreparedModImport(imports, index, importedCount, lastName, replaceExistingConflicts, true),
			() -> {
				repository.discardPreparedModImport(preparedImport);
				handlePreparedModImports(imports, index + 1, importedCount, lastName);
			});
	}

	private void commitPreparedModImport(List<ExtraSettingsRepository.PreparedModImport> imports, int index, int importedCount, String lastName, boolean replaceExistingConflicts, boolean allowPathConflicts) {
		if (busy) {
			return;
		}
		ExtraSettingsRepository.PreparedModImport preparedImport = imports.get(index);
		busy = true;
		showMessage(getString(R.string.status_busy_import_mod));
		new Thread(() -> {
			try {
				String importedName = repository.commitPreparedModImport(preparedImport, replaceExistingConflicts, allowPathConflicts);
				runOnUiThread(() -> {
					busy = false;
					handlePreparedModImports(imports, index + 1, importedCount + 1, importedName);
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					showError(exception);
				});
			}
		}, "sts2-mod-import-commit").start();
	}

	private void showModImportConflictDialog(ExtraSettingsRepository.PreparedModImport preparedImport, List<ExtraSettingsRepository.ModImportConflict> conflicts, Runnable keepOriginal, Runnable useNew, Runnable cancelImport) {
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		TextView message = ExtraSettingsUi.body(this, getString(R.string.mod_import_conflict_message));
		content.addView(message);
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
		new MaterialAlertDialogBuilder(this)
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
		new MaterialAlertDialogBuilder(this)
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
		boolean shouldPushSteam = SteamSettings.shouldPushAfterCleanExit(this) && SteamAuthStore.readAuthMaterial(this) != null;
		boolean shouldPushWebDav = WebDavSettings.shouldPushAfterCleanExit(this) && WebDavSettings.isConfigured(this);
		if (!SteamCleanExitTracker.consumeIfRecent(this)) {
			cleanExitMaintenanceChecked = true;
			return;
		}
		cleanExitMaintenanceChecked = true;
		runCleanExitSaveMaintenance(shouldPushSteam, shouldPushWebDav);
	}

	private void runCleanExitSaveMaintenance(boolean shouldPushSteam, boolean shouldPushWebDav) {
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
				runOnUiThread(() -> progressDialog.setProgress(0, getString(R.string.status_busy_create_local_save_snapshot)));
				LocalSaveSnapshotManager.Snapshot snapshot = new LocalSaveSnapshotManager(this).createAutomaticSnapshot("clean-exit");
				if (snapshot != null) {
					results.add(getString(R.string.status_local_save_snapshot_created, snapshot.id));
				}
				if (shouldPushSteam) {
					String result = new Sts2SteamCloudSyncManager(this).pushLocalChanges(false, (percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
					results.add(result);
				}
				if (shouldPushWebDav) {
					String result = new WebDavSyncManager(this).pushLocalChanges(false, (percent, message) -> runOnUiThread(() -> progressDialog.setProgress(percent, message)));
					results.add(result);
				}
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					if (!results.isEmpty()) {
						showMessage(String.join("\n", results));
					}
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					progressDialog.dismiss();
					showError(exception);
				});
			}
		}, "sts2-clean-exit-save-maintenance").start();
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
		new Thread(() -> {
			try {
				createPreLaunchLocalSaveSnapshotIfNeeded();
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
				runOnUiThread(() -> {
					bundledCompatPackBootstrapFinished = true;
					refreshCurrentScreen();
					runPendingLauncherDirectLaunch();
				});
			} catch (Exception exception) {
				Log.w(TAG, "Unable to install bundled compatibility packs.", exception);
				runOnUiThread(() -> {
					bundledCompatPackBootstrapFinished = true;
					runPendingLauncherDirectLaunch();
				});
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
