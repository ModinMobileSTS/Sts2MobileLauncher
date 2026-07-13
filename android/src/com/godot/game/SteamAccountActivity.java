package com.godot.game;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.godot.game.steam.auth.SteamAuthForegroundService;
import com.godot.game.steam.auth.SteamAuthStore;
import com.godot.game.steam.auth.SteamLoginCoordinator;
import com.godot.game.steam.cloud.Sts2SteamCloudSyncManager;
import com.godot.game.steam.core.SteamSettings;
import com.godot.game.steam.download.Sts2SteamPayloadDownloader;
import com.godot.game.steam.ui.SteamDownloadProgressPanel;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthenticationException;
import top.apricityx.workshop.steam.protocol.SteamAuthTransactionHandle;
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType;

public class SteamAccountActivity extends AppCompatActivity {
	private static final int SAFETY_NOTICE_COUNTDOWN_SECONDS = 5;
	private static final long SAFETY_NOTICE_COUNTDOWN_INTERVAL_MS = 1000L;
	private static final int TAB_DOWNLOAD = 0;
	private static final int TAB_CLOUD = 1;
	private static final int TAB_ACCOUNT = 2;
	private static final int BRANCH_PUBLIC = 0;
	private static final int BRANCH_BETA = 1;
	private static final int BRANCH_CUSTOM = 2;
	private static final int REQUEST_POST_NOTIFICATIONS = 4301;
	private static final String STEAM_ANDROID_PACKAGE = "com.valvesoftware.android.steam.community";

	private LinearLayout tabContent;
	private LinearLayout downloadPage;
	private LinearLayout cloudPage;
	private LinearLayout accountPage;
	private TextView profileNameView;
	private TextView profileIdView;
	private TextView profileBadgeView;
	private TextView accountProfileNameView;
	private TextView accountProfileIdView;
	private TextView accountProfileBadgeView;
	private TextView accountUsernameValueView;
	private TextView accountSteamIdValueView;
	private TextView tokenStatusView;
	private TextView accountLastErrorView;
	private LinearLayout accountLastErrorRow;
	private TextView cloudStatusBodyView;
	private TextView cloudPathView;
	private TextView cloudModeValueView;
	private MaterialSwitch settingsSaveSwitch;
	private View downloadSetupBlock;
	private SteamDownloadProgressPanel downloadProgressPanel;
	private MaterialCardView branchPublicCard;
	private MaterialCardView branchBetaCard;
	private MaterialCardView branchCustomCard;
	private LinearLayout branchCustomDetails;
	private TextInputEditText customBranchInput;
	private MaterialButton downloadButton;
	private MaterialButton loginButton;
	private MaterialButton verifyLoginButton;
	private MaterialButton logoutButton;
	private View radioPublic;
	private View radioBeta;
	private View radioCustom;
	private boolean busy;
	private boolean downloadingPayload;
	private int selectedTab = TAB_DOWNLOAD;
	private int selectedBranch = BRANCH_PUBLIC;
	private PayloadManager.ImportControl activeDownloadControl;
	private SteamOperationProgressDialog operationDialog;
	private SteamAuthForegroundService.LocalBinder steamAuthBinder;
	private boolean steamAuthServiceBound;
	private boolean steamAuthActive;
	private String pendingAuthUsername;
	private String pendingAuthPassword;
	private AlertDialog steamAuthDialog;
	private String steamAuthDialogKey = "";
	private String suppressedConfirmationTransactionId = "";
	private long lastHandledAuthTerminalRevision = -1L;

	private final SteamAuthForegroundService.Listener steamAuthListener = snapshot ->
		runOnUiThread(() -> renderSteamAuthSnapshot(snapshot));

	private final ServiceConnection steamAuthConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			if (!(service instanceof SteamAuthForegroundService.LocalBinder)) {
				return;
			}
			steamAuthBinder = (SteamAuthForegroundService.LocalBinder) service;
			steamAuthBinder.registerListener(steamAuthListener);
			consumePendingAuthCredentials();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			steamAuthBinder = null;
		}

		@Override
		public void onBindingDied(ComponentName name) {
			steamAuthBinder = null;
			steamAuthServiceBound = false;
			if (!isFinishing() && !isDestroyed()) {
				bindSteamAuthService();
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		buildUi();
		refreshStatus();
		showFirstOpenSafetyNoticeIfNeeded();
	}

	@Override
	protected void onStart() {
		super.onStart();
		// The foreground service may have completed while this Activity was stopped and unbound.
		// Refresh persisted account/error state before attaching to any still-active transaction.
		refreshStatusOnly();
		SteamAuthTransactionHandle pending = SteamAuthStore.readPendingAuthTransaction(this);
		if (pending != null) {
			if (pending.isExpired()) {
				SteamAuthStore.clearPendingAuthTransaction(this, pending.getTransactionId());
				showMessage(getString(R.string.steam_error_session_expired));
			} else {
				SteamAuthForegroundService.resumePending(this);
			}
		}
		bindSteamAuthService();
	}

	@Override
	protected void onStop() {
		// Credentials are intentionally not recoverable before BeginAuth has produced a persisted
		// handle. If they have not reached the binder yet, leaving the Activity drops them.
		pendingAuthUsername = null;
		pendingAuthPassword = null;
		dismissSteamAuthDialog();
		if (steamAuthBinder != null) {
			steamAuthBinder.unregisterListener(steamAuthListener);
			steamAuthBinder = null;
		}
		if (steamAuthServiceBound) {
			unbindService(steamAuthConnection);
			steamAuthServiceBound = false;
		}
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		pendingAuthUsername = null;
		pendingAuthPassword = null;
		dismissSteamAuthDialog();
		if (downloadProgressPanel != null) {
			downloadProgressPanel.stopAnimations();
		}
		super.onDestroy();
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
		LinearLayout shell = ExtraSettingsUi.vertical(this);
		shell.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		SystemBarInsetsHelper.applySystemBarPadding(shell, true, true, true, true);

		shell.addView(buildProfileHeader(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		shell.addView(buildSegmentedTabs(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		ScrollView scroll = new ScrollView(this);
		scroll.setFillViewport(false);
		scroll.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		tabContent = ExtraSettingsUi.vertical(this);
		tabContent.setPadding(0, ExtraSettingsUi.dp(this, 12), 0, ExtraSettingsUi.dp(this, 28));
		downloadPage = ExtraSettingsUi.vertical(this);
		cloudPage = ExtraSettingsUi.vertical(this);
		accountPage = ExtraSettingsUi.vertical(this);
		populateDownloadTab(downloadPage);
		populateCloudTab(cloudPage);
		populateAccountTab(accountPage);
		tabContent.addView(downloadPage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		tabContent.addView(cloudPage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		tabContent.addView(accountPage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		ExtraSettingsUi.addResponsiveScrollContent(this, scroll, tabContent);
		shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		setContentView(shell);
		showTab(selectedTab);
	}

	private View buildProfileHeader() {
		LinearLayout header = ExtraSettingsUi.horizontal(this);
		header.setGravity(Gravity.CENTER_VERTICAL);
		int padH = ExtraSettingsUi.pageHorizontalPadding(this);
		header.setPadding(padH, ExtraSettingsUi.dp(this, 14), padH, ExtraSettingsUi.dp(this, 10));
		header.setBackgroundColor(Color.argb(220, 25, 29, 38));

		header.addView(ExtraSettingsUi.iconCircle(this, R.drawable.ic_steam_24, ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER));

		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		header.addView(texts, textParams);

		LinearLayout nameRow = ExtraSettingsUi.horizontal(this);
		nameRow.setGravity(Gravity.CENTER_VERTICAL);
		profileNameView = ExtraSettingsUi.label(this, R.string.steam_not_logged_in);
		nameRow.addView(profileNameView);
		profileBadgeView = createBadge(getString(R.string.steam_center_offline_badge), false);
		LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		badgeParams.setMarginStart(ExtraSettingsUi.dp(this, 8));
		nameRow.addView(profileBadgeView, badgeParams);
		texts.addView(nameRow);

		profileIdView = ExtraSettingsUi.caption(this, getString(R.string.steam_center_steamid_format, getString(R.string.unknown)));
		profileIdView.setTypeface(Typeface.MONOSPACE);
		LinearLayout.LayoutParams idParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		idParams.topMargin = ExtraSettingsUi.dp(this, 2);
		texts.addView(profileIdView, idParams);

		MaterialButton logout = ExtraSettingsUi.iconButton(this, R.drawable.ic_delete_24);
		logout.setContentDescription(getString(R.string.steam_logout));
		logout.setOnClickListener(v -> confirmLogout());
		header.addView(logout);
		return header;
	}

	private View buildSegmentedTabs() {
		LinearLayout wrap = ExtraSettingsUi.vertical(this);
		int padH = ExtraSettingsUi.pageHorizontalPadding(this);
		wrap.setPadding(padH, ExtraSettingsUi.dp(this, 4), padH, ExtraSettingsUi.dp(this, 4));

		MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
		group.setSingleSelection(true);
		group.setSelectionRequired(true);
		group.setBackgroundColor(Color.TRANSPARENT);

		MaterialButton download = segmentedButton(R.string.steam_center_tab_download);
		MaterialButton cloud = segmentedButton(R.string.steam_center_tab_cloud);
		MaterialButton account = segmentedButton(R.string.steam_center_tab_account);
		download.setId(View.generateViewId());
		cloud.setId(View.generateViewId());
		account.setId(View.generateViewId());
		group.addView(download, segmentedParams());
		group.addView(cloud, segmentedParams());
		group.addView(account, segmentedParams());
		group.check(selectedTab == TAB_CLOUD ? cloud.getId() : (selectedTab == TAB_ACCOUNT ? account.getId() : download.getId()));
		group.addOnButtonCheckedListener((buttonGroup, checkedId, isChecked) -> {
			if (!isChecked) {
				return;
			}
			if (checkedId == cloud.getId()) {
				showTab(TAB_CLOUD);
			} else if (checkedId == account.getId()) {
				showTab(TAB_ACCOUNT);
			} else {
				showTab(TAB_DOWNLOAD);
			}
		});
		wrap.addView(group, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return wrap;
	}

	private MaterialButton segmentedButton(int textRes) {
		MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
		button.setText(textRes);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		button.setCheckable(true);
		button.setMinHeight(ExtraSettingsUi.dp(this, 44));
		button.setInsetTop(0);
		button.setInsetBottom(0);
		button.setPadding(ExtraSettingsUi.dp(this, 4), 0, ExtraSettingsUi.dp(this, 4), 0);
		button.setTextColor(new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT }
		));
		button.setBackgroundTintList(new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_SURFACE_VARIANT, Color.TRANSPARENT }
		));
		button.setStrokeColor(ColorStateList.valueOf(ExtraSettingsUi.COLOR_OUTLINE));
		button.setStrokeWidth(ExtraSettingsUi.dp(this, 1));
		button.setCornerRadius(ExtraSettingsUi.dp(this, 16));
		return button;
	}

	private LinearLayout.LayoutParams segmentedParams() {
		return new LinearLayout.LayoutParams(0, ExtraSettingsUi.dp(this, 46), 1f);
	}

	private void showTab(int tab) {
		selectedTab = tab;
		if (downloadPage != null) {
			downloadPage.setVisibility(tab == TAB_DOWNLOAD ? View.VISIBLE : View.GONE);
		}
		if (cloudPage != null) {
			cloudPage.setVisibility(tab == TAB_CLOUD ? View.VISIBLE : View.GONE);
		}
		if (accountPage != null) {
			accountPage.setVisibility(tab == TAB_ACCOUNT ? View.VISIBLE : View.GONE);
		}
		refreshStatusOnly();
		updateDownloadUiVisibility();
	}

	private void populateDownloadTab(LinearLayout root) {
		ExtraSettingsUi.addCardSpacing(root, buildBranchSection());

		downloadSetupBlock = ExtraSettingsUi.vertical(this);
		MaterialCardView setupCard = ExtraSettingsUi.card(this);
		LinearLayout setupContent = ExtraSettingsUi.cardContent(this, setupCard);
		downloadButton = ExtraSettingsUi.filledButton(this, R.string.steam_payload_download_button, R.drawable.ic_download_24);
		downloadButton.setOnClickListener(v -> startPayloadDownload());
		setupContent.addView(downloadButton);
		((LinearLayout) downloadSetupBlock).addView(setupCard);
		ExtraSettingsUi.addCardSpacing(root, downloadSetupBlock);

		downloadProgressPanel = new SteamDownloadProgressPanel(this);
		downloadProgressPanel.setCancelListener(v -> {
			if (activeDownloadControl != null) {
				activeDownloadControl.cancel();
				downloadProgressPanel.setCancelEnabled(false);
			}
		});
		downloadProgressPanel.getView().setVisibility(View.GONE);
		ExtraSettingsUi.addCardSpacing(root, downloadProgressPanel.getView());
	}

	private View buildBranchSection() {
		LinearLayout section = ExtraSettingsUi.vertical(this);
		TextView title = ExtraSettingsUi.caption(this, getString(R.string.steam_branch_section_title).toUpperCase(Locale.getDefault()));
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setLetterSpacing(0.08f);
		section.addView(title);

		branchPublicCard = buildBranchCard(
			BRANCH_PUBLIC,
			R.string.steam_branch_public_label,
			R.string.steam_branch_public_badge,
			R.string.steam_branch_public_desc,
			false
		);
		branchBetaCard = buildBranchCard(
			BRANCH_BETA,
			R.string.steam_branch_beta_label,
			R.string.steam_branch_beta_badge,
			R.string.steam_branch_beta_desc,
			true
		);
		branchCustomCard = buildBranchCard(
			BRANCH_CUSTOM,
			R.string.steam_branch_custom_label,
			R.string.steam_branch_custom_badge,
			R.string.steam_branch_custom_desc,
			false
		);
		LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		cardParams.topMargin = ExtraSettingsUi.dp(this, 8);
		section.addView(branchPublicCard, cardParams);
		section.addView(branchBetaCard, copyParams(cardParams));
		section.addView(branchCustomCard, copyParams(cardParams));
		applyBranchSelection();
		return section;
	}

	private LinearLayout.LayoutParams copyParams(LinearLayout.LayoutParams source) {
		LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(source.width, source.height);
		copy.topMargin = source.topMargin;
		return copy;
	}

	private MaterialCardView buildBranchCard(int branch, int labelRes, int badgeRes, int descRes, boolean warningBadge) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		// Base gray stroke; styleBranchCard refreshes selected/unselected colors.
		card.setStrokeWidth(ExtraSettingsUi.dp(this, 2));
		card.setStrokeColor(Color.rgb(90, 98, 112));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 14));

		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		View radio = createRadioDot(false);
		if (branch == BRANCH_PUBLIC) {
			radioPublic = radio;
		} else if (branch == BRANCH_BETA) {
			radioBeta = radio;
		} else {
			radioCustom = radio;
		}
		row.addView(radio);

		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		row.addView(texts, textParams);

		LinearLayout titleRow = ExtraSettingsUi.horizontal(this);
		titleRow.setGravity(Gravity.CENTER_VERTICAL);
		TextView label = ExtraSettingsUi.label(this, labelRes);
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		titleRow.addView(label);
		TextView badge = createBadge(getString(badgeRes), !warningBadge);
		if (warningBadge) {
			badge.setTextColor(ExtraSettingsUi.COLOR_WARNING);
			GradientDrawable bg = new GradientDrawable();
			bg.setColor(Color.argb(60, 255, 201, 111));
			bg.setCornerRadius(ExtraSettingsUi.dp(this, 8));
			badge.setBackground(bg);
		}
		LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		badgeParams.setMarginStart(ExtraSettingsUi.dp(this, 8));
		titleRow.addView(badge, badgeParams);
		texts.addView(titleRow);

		TextView desc = ExtraSettingsUi.caption(this, getString(descRes));
		LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		descParams.topMargin = ExtraSettingsUi.dp(this, 3);
		texts.addView(desc, descParams);
		content.addView(row);

		if (branch == BRANCH_CUSTOM) {
			branchCustomDetails = ExtraSettingsUi.vertical(this);
			branchCustomDetails.setVisibility(View.GONE);
			LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			detailsParams.topMargin = ExtraSettingsUi.dp(this, 12);
			content.addView(branchCustomDetails, detailsParams);
			branchCustomDetails.addView(ExtraSettingsUi.divider(this));
			TextInputLayout inputLayout = new TextInputLayout(this);
			inputLayout.setHint(getString(R.string.steam_branch_custom_hint));
			inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
			inputLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
			customBranchInput = new TextInputEditText(inputLayout.getContext());
			customBranchInput.setSingleLine(true);
			customBranchInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			customBranchInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
			inputLayout.addView(customBranchInput);
			LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			inputParams.topMargin = ExtraSettingsUi.dp(this, 10);
			branchCustomDetails.addView(inputLayout, inputParams);
		}

		card.setOnClickListener(v -> {
			if (busy) {
				return;
			}
			selectedBranch = branch;
			applyBranchSelection();
		});
		return card;
	}

	private void applyBranchSelection() {
		styleBranchCard(branchPublicCard, radioPublic, selectedBranch == BRANCH_PUBLIC);
		styleBranchCard(branchBetaCard, radioBeta, selectedBranch == BRANCH_BETA);
		styleBranchCard(branchCustomCard, radioCustom, selectedBranch == BRANCH_CUSTOM);
		if (branchCustomDetails != null) {
			branchCustomDetails.setVisibility(selectedBranch == BRANCH_CUSTOM ? View.VISIBLE : View.GONE);
		}
	}

	private void styleBranchCard(MaterialCardView card, View radio, boolean selected) {
		if (card == null) {
			return;
		}
		// Unselected: gray outline; selected: theme primary outline (clearer than bg alone).
		int grayStroke = Color.rgb(90, 98, 112);
		card.setStrokeWidth(ExtraSettingsUi.dp(this, selected ? 2.5f : 2f));
		card.setStrokeColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : grayStroke);
		card.setCardBackgroundColor(selected ? Color.rgb(30, 50, 39) : Color.rgb(24, 28, 36));
		if (radio instanceof ViewGroup) {
			View inner = ((ViewGroup) radio).getChildCount() > 0 ? ((ViewGroup) radio).getChildAt(0) : null;
			if (inner != null) {
				GradientDrawable fill = new GradientDrawable();
				fill.setShape(GradientDrawable.OVAL);
				fill.setColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : Color.TRANSPARENT);
				inner.setBackground(fill);
			}
			GradientDrawable outer = new GradientDrawable();
			outer.setShape(GradientDrawable.OVAL);
			outer.setStroke(ExtraSettingsUi.dp(this, 2), selected ? ExtraSettingsUi.COLOR_PRIMARY : grayStroke);
			outer.setColor(Color.TRANSPARENT);
			radio.setBackground(outer);
		}
	}

	private View createRadioDot(boolean selected) {
		FrameLayout outer = new FrameLayout(this);
		int size = ExtraSettingsUi.dp(this, 20);
		outer.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		GradientDrawable ring = new GradientDrawable();
		ring.setShape(GradientDrawable.OVAL);
		ring.setStroke(ExtraSettingsUi.dp(this, 2), selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_OUTLINE);
		ring.setColor(Color.TRANSPARENT);
		outer.setBackground(ring);
		View inner = new View(this);
		int innerSize = ExtraSettingsUi.dp(this, 10);
		FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER);
		GradientDrawable fill = new GradientDrawable();
		fill.setShape(GradientDrawable.OVAL);
		fill.setColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : Color.TRANSPARENT);
		inner.setBackground(fill);
		outer.addView(inner, innerParams);
		return outer;
	}

	private void populateCloudTab(LinearLayout root) {
		MaterialCardView statusCard = ExtraSettingsUi.card(this);
		LinearLayout statusContent = ExtraSettingsUi.cardContent(this, statusCard);
		statusContent.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_cloud_sync_24, R.string.steam_cloud_title, R.string.steam_cloud_subtitle, null));
		cloudStatusBodyView = ExtraSettingsUi.body(this, "");
		ExtraSettingsUi.addSmallSpacing(statusContent, cloudStatusBodyView);

		TextView pathLabel = ExtraSettingsUi.caption(this, getString(R.string.steam_cloud_path_label));
		ExtraSettingsUi.addSmallSpacing(statusContent, pathLabel);
		LinearLayout pathRow = ExtraSettingsUi.horizontal(this);
		pathRow.setGravity(Gravity.CENTER_VERTICAL);
		pathRow.setPadding(ExtraSettingsUi.dp(this, 10), ExtraSettingsUi.dp(this, 10), ExtraSettingsUi.dp(this, 10), ExtraSettingsUi.dp(this, 10));
		GradientDrawable pathBg = new GradientDrawable();
		pathBg.setColor(ExtraSettingsUi.COLOR_SURFACE);
		pathBg.setCornerRadius(ExtraSettingsUi.dp(this, 12));
		pathBg.setStroke(ExtraSettingsUi.dp(this, 1), ExtraSettingsUi.COLOR_OUTLINE);
		pathRow.setBackground(pathBg);
		cloudPathView = ExtraSettingsUi.caption(this, "");
		cloudPathView.setTypeface(Typeface.MONOSPACE);
		cloudPathView.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		pathRow.addView(cloudPathView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		MaterialButton copy = ExtraSettingsUi.iconButton(this, R.drawable.ic_content_copy_24);
		copy.setContentDescription(getString(R.string.file_browser_copy));
		copy.setOnClickListener(v -> {
			CharSequence path = cloudPathView.getText();
			if (!TextUtils.isEmpty(path)) {
				ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				if (clipboard != null) {
					clipboard.setPrimaryClip(ClipData.newPlainText("steam-cloud-path", path));
					showMessage(getString(R.string.steam_cloud_path_copied));
				}
			}
		});
		pathRow.addView(copy);
		ExtraSettingsUi.addSmallSpacing(statusContent, pathRow);

		MaterialCardView modeRow = ExtraSettingsUi.clickableCard(this);
		LinearLayout modeContent = ExtraSettingsUi.cardContent(this, modeRow);
		LinearLayout modeInner = ExtraSettingsUi.horizontal(this);
		modeInner.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout modeTexts = ExtraSettingsUi.vertical(this);
		modeTexts.addView(ExtraSettingsUi.label(this, R.string.steam_cloud_mode_title));
		cloudModeValueView = ExtraSettingsUi.caption(this, cloudModeLabel(SteamSettings.getCloudMode(this)));
		LinearLayout.LayoutParams modeValueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		modeValueParams.topMargin = ExtraSettingsUi.dp(this, 2);
		modeTexts.addView(cloudModeValueView, modeValueParams);
		modeInner.addView(modeTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		modeInner.addView(ExtraSettingsUi.icon(this, R.drawable.ic_chevron_right_24, ExtraSettingsUi.COLOR_MUTED, 20));
		modeContent.addView(modeInner);
		// Nested card clicks can be flaky; also surface busy state instead of silent no-op.
		View.OnClickListener openModeSheet = v -> {
			if (busy) {
				showMessage(getString(R.string.steam_status_cloud_busy));
				return;
			}
			showCloudModeBottomSheet();
		};
		modeRow.setOnClickListener(openModeSheet);
		modeContent.setOnClickListener(openModeSheet);
		modeInner.setOnClickListener(openModeSheet);
		ExtraSettingsUi.addSmallSpacing(statusContent, modeRow);

		settingsSaveSwitch = new MaterialSwitch(this);
		settingsSaveSwitch.setText(R.string.steam_cloud_sync_settings_save);
		settingsSaveSwitch.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		settingsSaveSwitch.setChecked(SteamSettings.shouldSyncSettingsSave(this));
		settingsSaveSwitch.setOnCheckedChangeListener((button, checked) -> SteamSettings.setSyncSettingsSave(this, checked));
		ExtraSettingsUi.addSmallSpacing(statusContent, settingsSaveSwitch);
		TextView settingsHint = ExtraSettingsUi.caption(this, getString(R.string.steam_cloud_sync_settings_save_hint));
		ExtraSettingsUi.addSmallSpacing(statusContent, settingsHint);
		ExtraSettingsUi.addCardSpacing(root, statusCard);

		LinearLayout actions = ExtraSettingsUi.vertical(this);
		LinearLayout row1 = ExtraSettingsUi.horizontal(this);
		row1.addView(buildCloudActionTile(R.drawable.ic_sync_24, R.string.steam_cloud_refresh, R.string.steam_cloud_action_refresh_hint, v -> runCloudOperation(operation -> operation.refreshManifest(this::setProgress))), gridParams(0));
		row1.addView(buildCloudActionTile(R.drawable.ic_download_24, R.string.steam_cloud_pull, R.string.steam_cloud_action_pull_hint, v -> confirmCloudOverwrite()), gridParams(8));
		actions.addView(row1);
		LinearLayout row2 = ExtraSettingsUi.horizontal(this);
		LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		row2Params.topMargin = ExtraSettingsUi.dp(this, 8);
		actions.addView(row2, row2Params);
		row2.addView(buildCloudActionTile(R.drawable.ic_upload_file_24, R.string.steam_cloud_push, R.string.steam_cloud_action_push_hint, v -> runCloudOperationWithConflictPrompt(operation -> operation.pushLocalChanges(false, this::setProgress))), gridParams(0));
		row2.addView(buildCloudActionTile(R.drawable.ic_upload_file_24, R.string.steam_cloud_force_push, R.string.steam_cloud_action_force_hint, v -> confirmForcePush()), gridParams(8));
		ExtraSettingsUi.addCardSpacing(root, actions);
	}

	private View buildCloudActionTile(int iconRes, int titleRes, int hintRes, View.OnClickListener listener) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 16));
		content.addView(ExtraSettingsUi.iconCircle(this, iconRes, ExtraSettingsUi.COLOR_SECONDARY_CONTAINER, ExtraSettingsUi.COLOR_PRIMARY));
		TextView title = ExtraSettingsUi.label(this, titleRes);
		title.setGravity(Gravity.CENTER);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		titleParams.topMargin = ExtraSettingsUi.dp(this, 10);
		content.addView(title, titleParams);
		TextView hint = ExtraSettingsUi.caption(this, getString(hintRes));
		hint.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		hintParams.topMargin = ExtraSettingsUi.dp(this, 4);
		content.addView(hint, hintParams);
		card.setOnClickListener(listener);
		return card;
	}

	private LinearLayout.LayoutParams gridParams(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		if (marginStartDp > 0) {
			params.setMarginStart(ExtraSettingsUi.dp(this, marginStartDp));
		}
		return params;
	}

	private void populateAccountTab(LinearLayout root) {
		// Discord-style profile: gradient banner stays behind the avatar ring.
		MaterialCardView profileCard = ExtraSettingsUi.card(this);
		profileCard.setPadding(0, 0, 0, 0);
		profileCard.setClipChildren(false);
		profileCard.setClipToPadding(false);
		profileCard.setClipToOutline(false);
		LinearLayout profileRoot = ExtraSettingsUi.vertical(this);
		profileRoot.setClipChildren(false);
		profileRoot.setClipToPadding(false);
		profileCard.addView(profileRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		int bannerHeight = ExtraSettingsUi.dp(this, 96);
		int avatarSize = ExtraSettingsUi.dp(this, 80);
		int avatarOverlap = avatarSize / 2;
		FrameLayout header = new FrameLayout(this);
		header.setClipChildren(false);
		header.setClipToPadding(false);
		// Extra height so the avatar (half on banner) is not clipped by siblings.
		LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bannerHeight + avatarOverlap + ExtraSettingsUi.dp(this, 8));
		profileRoot.addView(header, headerParams);

		View banner = new View(this);
		GradientDrawable bannerBg = new GradientDrawable(
			GradientDrawable.Orientation.TL_BR,
			new int[] { Color.rgb(31, 79, 49), Color.rgb(25, 29, 38), Color.rgb(42, 47, 61) }
		);
		banner.setBackground(bannerBg);
		header.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bannerHeight));

		FrameLayout avatarWrap = new FrameLayout(this);
		GradientDrawable avatarRing = new GradientDrawable();
		avatarRing.setShape(GradientDrawable.OVAL);
		avatarRing.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		avatarRing.setStroke(ExtraSettingsUi.dp(this, 5), ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		avatarWrap.setBackground(avatarRing);
		avatarWrap.setElevation(ExtraSettingsUi.dp(this, 6));
		FrameLayout.LayoutParams avatarWrapParams = new FrameLayout.LayoutParams(avatarSize, avatarSize);
		avatarWrapParams.gravity = Gravity.START | Gravity.TOP;
		avatarWrapParams.leftMargin = ExtraSettingsUi.dp(this, 18);
		avatarWrapParams.topMargin = bannerHeight - avatarOverlap;
		header.addView(avatarWrap, avatarWrapParams);

		// Inner circle sized smaller than ring so the ring stroke remains visible.
		LinearLayout avatarInner = new LinearLayout(this);
		avatarInner.setGravity(Gravity.CENTER);
		GradientDrawable avatarFill = new GradientDrawable();
		avatarFill.setShape(GradientDrawable.OVAL);
		avatarFill.setColor(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER);
		avatarInner.setBackground(avatarFill);
		int innerSize = avatarSize - ExtraSettingsUi.dp(this, 10);
		FrameLayout.LayoutParams avatarInnerParams = new FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER);
		avatarWrap.addView(avatarInner, avatarInnerParams);
		android.widget.ImageView steamIcon = ExtraSettingsUi.icon(this, R.drawable.ic_steam_24, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER, 36);
		avatarInner.addView(steamIcon);

		LinearLayout body = ExtraSettingsUi.vertical(this);
		body.setPadding(ExtraSettingsUi.dp(this, 18), ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 18), ExtraSettingsUi.dp(this, 18));
		profileRoot.addView(body);

		LinearLayout nameRow = ExtraSettingsUi.horizontal(this);
		nameRow.setGravity(Gravity.CENTER_VERTICAL);
		accountProfileNameView = ExtraSettingsUi.text(this, getString(R.string.steam_not_logged_in), 22, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		nameRow.addView(accountProfileNameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		accountProfileBadgeView = createBadge(getString(R.string.steam_center_offline_badge), false);
		nameRow.addView(accountProfileBadgeView);
		body.addView(nameRow);

		accountProfileIdView = ExtraSettingsUi.caption(this, getString(R.string.steam_center_steamid_format, getString(R.string.unknown)));
		accountProfileIdView.setTypeface(Typeface.MONOSPACE);
		LinearLayout.LayoutParams idParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		idParams.topMargin = ExtraSettingsUi.dp(this, 4);
		body.addView(accountProfileIdView, idParams);

		MaterialCardView fieldsCard = ExtraSettingsUi.card(this);
		fieldsCard.setRadius(ExtraSettingsUi.dp(this, 16));
		fieldsCard.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		LinearLayout fields = ExtraSettingsUi.cardContent(this, fieldsCard);
		fields.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
		accountUsernameValueView = addProfileField(fields, R.string.steam_account_profile_username, getString(R.string.steam_not_logged_in), false);
		fields.addView(ExtraSettingsUi.divider(this), dividerParams());
		accountSteamIdValueView = addProfileField(fields, R.string.steam_account_profile_steamid, getString(R.string.unknown), true);
		fields.addView(ExtraSettingsUi.divider(this), dividerParams());
		tokenStatusView = addProfileField(fields, R.string.steam_account_profile_token, getString(R.string.steam_account_token_status_empty), false);
		accountLastErrorRow = ExtraSettingsUi.vertical(this);
		accountLastErrorRow.setVisibility(View.GONE);
		accountLastErrorRow.addView(ExtraSettingsUi.divider(this), dividerParams());
		accountLastErrorView = addProfileField(accountLastErrorRow, R.string.steam_account_profile_last_error, "", false);
		accountLastErrorView.setTextColor(ExtraSettingsUi.COLOR_ERROR);
		fields.addView(accountLastErrorRow);
		LinearLayout.LayoutParams fieldsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		fieldsParams.topMargin = ExtraSettingsUi.dp(this, 16);
		body.addView(fieldsCard, fieldsParams);

		LinearLayout actions = ExtraSettingsUi.horizontal(this);
		loginButton = ExtraSettingsUi.tonalButton(this, R.string.steam_login, R.drawable.ic_badge_24);
		verifyLoginButton = ExtraSettingsUi.outlineButton(this, R.string.steam_verify_login, R.drawable.ic_check_circle_24);
		loginButton.setOnClickListener(v -> showLoginDialog());
		verifyLoginButton.setOnClickListener(v -> runOperation(getString(R.string.steam_status_verifying), () -> {
			String steamId = SteamLoginCoordinator.verifyRefreshToken(this);
			return getString(R.string.steam_status_verified, steamId);
		}));
		actions.addView(loginButton, weighted(0));
		actions.addView(verifyLoginButton, weighted(10));
		LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		actionsParams.topMargin = ExtraSettingsUi.dp(this, 14);
		body.addView(actions, actionsParams);

		logoutButton = ExtraSettingsUi.outlineButton(this, R.string.steam_logout, R.drawable.ic_delete_24);
		logoutButton.setOnClickListener(v -> confirmLogout());
		LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		logoutParams.topMargin = ExtraSettingsUi.dp(this, 8);
		body.addView(logoutButton, logoutParams);

		ExtraSettingsUi.addCardSpacing(root, profileCard);

		MaterialButton openSafety = ExtraSettingsUi.outlineButton(this, R.string.steam_account_safety_notice_open, R.drawable.ic_info_24);
		openSafety.setOnClickListener(v -> showSafetyNoticeDialog(false));
		ExtraSettingsUi.addCardSpacing(root, openSafety);
	}

	private TextView addProfileField(LinearLayout parent, int labelRes, String value, boolean mono) {
		LinearLayout field = ExtraSettingsUi.vertical(this);
		TextView label = ExtraSettingsUi.caption(this, getString(labelRes).toUpperCase(Locale.getDefault()));
		label.setTypeface(Typeface.DEFAULT_BOLD);
		label.setLetterSpacing(0.06f);
		field.addView(label);
		TextView valueView = ExtraSettingsUi.text(this, value == null ? "" : value, 15, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		if (mono) {
			valueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
		}
		LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		valueParams.topMargin = ExtraSettingsUi.dp(this, 3);
		field.addView(valueView, valueParams);
		parent.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return valueView;
	}

	private LinearLayout.LayoutParams dividerParams() {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(this, 1));
		params.topMargin = ExtraSettingsUi.dp(this, 10);
		params.bottomMargin = ExtraSettingsUi.dp(this, 10);
		return params;
	}

	private void confirmLogout() {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_logout_confirm_title)
			.setMessage(R.string.steam_logout_confirm_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				if (steamAuthBinder != null) {
					steamAuthBinder.cancel();
				} else if (steamAuthActive || SteamAuthStore.readPendingAuthTransaction(this) != null) {
					SteamAuthForegroundService.cancelPending(this);
				}
				SteamAuthStore.clear(this);
				steamAuthActive = false;
				updateAuthActionButtons();
				refreshStatus();
				showMessage(getString(R.string.steam_logged_out));
			})
			.show();
	}

	private void showCloudModeBottomSheet() {
		try {
			List<String> values = Arrays.asList(
				SteamSettings.CLOUD_MODE_OFF,
				SteamSettings.CLOUD_MODE_MANUAL,
				SteamSettings.CLOUD_MODE_PULL_ON_LAUNCH,
				SteamSettings.CLOUD_MODE_FULL_AUTO
			);
			List<String> labels = Arrays.asList(
				getString(R.string.steam_cloud_mode_off),
				getString(R.string.steam_cloud_mode_manual),
				getString(R.string.steam_cloud_mode_pull_on_launch),
				getString(R.string.steam_cloud_mode_full_auto)
			);
			List<String> descriptions = Arrays.asList(
				getString(R.string.steam_cloud_mode_off_desc),
				getString(R.string.steam_cloud_mode_manual_desc),
				getString(R.string.steam_cloud_mode_pull_on_launch_desc),
				getString(R.string.steam_cloud_mode_full_auto_desc)
			);
			int[] icons = {
				R.drawable.ic_close_24,
				R.drawable.ic_touch_app_24,
				R.drawable.ic_download_24,
				R.drawable.ic_cloud_sync_24
			};
			String current = SteamSettings.getCloudMode(this);
			BottomSheetDialog dialog = new BottomSheetDialog(this);
			LinearLayout sheetRoot = ExtraSettingsUi.vertical(this);
			sheetRoot.setBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
			int pad = ExtraSettingsUi.dp(this, 20);
			sheetRoot.setPadding(pad, ExtraSettingsUi.dp(this, 12), pad, ExtraSettingsUi.dp(this, 28));

			View handle = new View(this);
			GradientDrawable handleBg = new GradientDrawable();
			handleBg.setColor(ExtraSettingsUi.COLOR_OUTLINE);
			handleBg.setCornerRadius(ExtraSettingsUi.dp(this, 100));
			handle.setBackground(handleBg);
			LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 36), ExtraSettingsUi.dp(this, 4));
			handleParams.gravity = Gravity.CENTER_HORIZONTAL;
			handleParams.bottomMargin = ExtraSettingsUi.dp(this, 12);
			sheetRoot.addView(handle, handleParams);
			sheetRoot.addView(ExtraSettingsUi.sectionTitle(this, R.string.steam_cloud_mode_title));

			for (int i = 0; i < values.size(); i++) {
				final int index = i;
				boolean selected = values.get(i).equals(current);
				MaterialCardView option = ExtraSettingsUi.clickableCard(this);
				option.setStrokeWidth(ExtraSettingsUi.dp(this, selected ? 2.5f : 2f));
				option.setStrokeColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : Color.rgb(90, 98, 112));
				option.setCardBackgroundColor(selected ? Color.rgb(30, 50, 39) : Color.rgb(24, 28, 36));
				LinearLayout optionContent = ExtraSettingsUi.cardContent(this, option);
				LinearLayout row = ExtraSettingsUi.horizontal(this);
				row.setGravity(Gravity.CENTER_VERTICAL);
				int iconBg = selected ? ExtraSettingsUi.COLOR_PRIMARY_CONTAINER : ExtraSettingsUi.COLOR_SECONDARY_CONTAINER;
				int iconTint = selected ? ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT;
				row.addView(ExtraSettingsUi.iconCircle(this, icons[i], iconBg, iconTint));
				LinearLayout texts = ExtraSettingsUi.vertical(this);
				LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
				textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
				row.addView(texts, textParams);
				TextView label = ExtraSettingsUi.sectionTitle(this, labels.get(i));
				label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
				texts.addView(label);
				TextView desc = ExtraSettingsUi.caption(this, descriptions.get(i));
				LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
				descParams.topMargin = ExtraSettingsUi.dp(this, 4);
				texts.addView(desc, descParams);
				optionContent.addView(row);
				option.setOnClickListener(v -> {
					SteamSettings.setCloudMode(this, values.get(index));
					refreshStatusOnly();
					dialog.dismiss();
				});
				LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
				optionParams.topMargin = ExtraSettingsUi.dp(this, 10);
				sheetRoot.addView(option, optionParams);
			}

			ScrollView scroll = new ScrollView(this);
			scroll.setFillViewport(true);
			scroll.addView(sheetRoot, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			dialog.setContentView(scroll);
			dialog.setOnShowListener(shown -> {
				FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
				if (bottomSheet != null) {
					bottomSheet.setBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
					ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
					if (params != null) {
						params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
						bottomSheet.setLayoutParams(params);
					}
					com.google.android.material.bottomsheet.BottomSheetBehavior<FrameLayout> behavior =
						com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
					behavior.setSkipCollapsed(true);
					behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
					behavior.setDraggable(true);
				}
				if (dialog.getWindow() != null) {
					dialog.getWindow().setDimAmount(0.48f);
				}
			});
			dialog.show();
		} catch (RuntimeException error) {
			android.util.Log.e("Sts2Re", "showCloudModeBottomSheet failed", error);
			// Fallback if BottomSheet cannot show: simple list dialog keeps mode selectable.
			List<String> labels = Arrays.asList(
				getString(R.string.steam_cloud_mode_off),
				getString(R.string.steam_cloud_mode_manual),
				getString(R.string.steam_cloud_mode_pull_on_launch),
				getString(R.string.steam_cloud_mode_full_auto)
			);
			List<String> values = Arrays.asList(
				SteamSettings.CLOUD_MODE_OFF,
				SteamSettings.CLOUD_MODE_MANUAL,
				SteamSettings.CLOUD_MODE_PULL_ON_LAUNCH,
				SteamSettings.CLOUD_MODE_FULL_AUTO
			);
			int selected = Math.max(0, values.indexOf(SteamSettings.getCloudMode(this)));
			new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.steam_cloud_mode_title)
				.setSingleChoiceItems(labels.toArray(new String[0]), selected, (d, which) -> {
					SteamSettings.setCloudMode(this, values.get(which));
					refreshStatusOnly();
					d.dismiss();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
		}
	}

	private void showLoginDialog() {
		if (busy) {
			return;
		}
		SteamAuthTransactionHandle pending = SteamAuthStore.readPendingAuthTransaction(this);
		if (pending != null && !pending.isExpired()) {
			SteamAuthForegroundService.resumePending(this);
			bindSteamAuthService();
			showMessage(getString(R.string.steam_status_auth_resuming));
			return;
		}
		if (steamAuthActive) {
			if (steamAuthBinder != null) {
				renderSteamAuthSnapshot(steamAuthBinder.getSnapshot());
			}
			return;
		}
		LinearLayout content = ExtraSettingsUi.vertical(this);
		int padding = ExtraSettingsUi.dp(this, 8);
		content.setPadding(padding, padding, padding, 0);

		TextInputLayout usernameLayout = new TextInputLayout(this);
		usernameLayout.setHint(getString(R.string.steam_username_hint));
		usernameLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
		TextInputEditText username = new TextInputEditText(usernameLayout.getContext());
		username.setSingleLine(true);
		username.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		usernameLayout.addView(username);
		content.addView(usernameLayout);

		TextInputLayout passwordLayout = new TextInputLayout(this);
		passwordLayout.setHint(getString(R.string.steam_password_hint));
		passwordLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
		TextInputEditText password = new TextInputEditText(passwordLayout.getContext());
		password.setSingleLine(true);
		password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		password.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		passwordLayout.addView(password);
		LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		passwordParams.topMargin = ExtraSettingsUi.dp(this, 10);
		content.addView(passwordLayout, passwordParams);

		SteamAuthStore.AuthSnapshot snapshot = SteamAuthStore.readSnapshot(this);
		if (!TextUtils.isEmpty(snapshot.accountName)) {
			username.setText(snapshot.accountName);
			username.setSelection(username.getText() == null ? 0 : username.getText().length());
		}
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_login)
			.setMessage(R.string.steam_login_message)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.steam_login, null)
			.create();
		dialog.setOnShowListener(shown -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
			String user = username.getText() == null ? "" : username.getText().toString().trim();
			String pass = password.getText() == null ? "" : password.getText().toString();
			if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
				showMessage(getString(R.string.steam_auth_error_missing_credentials));
				return;
			}
			password.setText("");
			dialog.dismiss();
			startSteamAuthentication(user, pass);
		}));
		dialog.show();
	}

	private void startSteamAuthentication(String username, String password) {
		pendingAuthUsername = username;
		pendingAuthPassword = password;
		steamAuthActive = true;
		updateAuthActionButtons();
		try {
			// Start the foreground owner before handing credentials to its local binder. Neither the
			// password nor the Guard code is ever placed in this Intent.
			SteamAuthForegroundService.prepare(this);
			bindSteamAuthService();
			consumePendingAuthCredentials();
			requestSteamAuthNotificationPermissionIfNeeded();
		} catch (RuntimeException error) {
			pendingAuthUsername = null;
			pendingAuthPassword = null;
			steamAuthActive = false;
			updateAuthActionButtons();
			showMessage(getString(R.string.error_operation_failed) + ": " + rawErrorMessage(error));
		}
	}

	private void bindSteamAuthService() {
		if (steamAuthServiceBound) {
			return;
		}
		Intent intent = new Intent(this, SteamAuthForegroundService.class);
		steamAuthServiceBound = bindService(intent, steamAuthConnection, Context.BIND_AUTO_CREATE);
		if (!steamAuthServiceBound && pendingAuthPassword != null) {
			pendingAuthUsername = null;
			pendingAuthPassword = null;
			steamAuthActive = false;
			updateAuthActionButtons();
			showMessage(getString(R.string.steam_auth_error_missing_credentials));
		}
	}

	private void consumePendingAuthCredentials() {
		if (steamAuthBinder == null || pendingAuthUsername == null || pendingAuthPassword == null) {
			return;
		}
		String username = pendingAuthUsername;
		String password = pendingAuthPassword;
		pendingAuthUsername = null;
		pendingAuthPassword = null;
		steamAuthBinder.begin(username, password);
	}

	private void requestSteamAuthNotificationPermissionIfNeeded() {
		if (
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
		) {
			ActivityCompat.requestPermissions(
				this,
				new String[] {Manifest.permission.POST_NOTIFICATIONS},
				REQUEST_POST_NOTIFICATIONS
			);
		}
	}

	private void renderSteamAuthSnapshot(SteamAuthForegroundService.Snapshot snapshot) {
		if (!steamAuthServiceBound || isFinishing() || isDestroyed()) {
			return;
		}
		steamAuthActive = snapshot.isActive();
		updateAuthActionButtons();
		switch (snapshot.getStage()) {
			case IDLE:
				return;
			case PREPARING:
			case STARTING:
			case RESUMING:
			case POLLING:
			case RECONNECTING:
			case SUBMITTING_CODE:
				showSteamAuthStatusDialog(snapshot);
				return;
			case WAITING_CONFIRMATION:
				showSteamConfirmationDialog(snapshot);
				return;
			case WAITING_CODE:
				showSteamGuardCodeDialog(snapshot);
				return;
			case SUCCESS:
			case FAILED:
			case CANCELLED:
			case EXPIRED:
			case NEEDS_CREDENTIALS:
				handleSteamAuthTerminalSnapshot(snapshot);
				return;
		}
	}

	private void showSteamAuthStatusDialog(SteamAuthForegroundService.Snapshot snapshot) {
		String key = "status:" + snapshot.getStage().name();
		if (steamAuthDialog != null && key.equals(steamAuthDialogKey)) {
			steamAuthDialog.setMessage(snapshot.getMessage());
			return;
		}
		dismissSteamAuthDialog();
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_login)
			.setMessage(snapshot.getMessage())
			.setCancelable(false)
			.setNegativeButton(R.string.steam_guard_cancel_login_button, (ignored, which) -> cancelSteamAuthentication())
			.create();
		showSteamAuthDialog(dialog, key);
	}

	private void showSteamConfirmationDialog(SteamAuthForegroundService.Snapshot snapshot) {
		String transactionId = snapshot.getTransactionId() == null ? "" : snapshot.getTransactionId();
		if (transactionId.equals(suppressedConfirmationTransactionId)) {
			dismissSteamAuthDialog();
			return;
		}
		String key = "confirmation:" + transactionId;
		if (steamAuthDialog != null && key.equals(steamAuthDialogKey)) {
			steamAuthDialog.setMessage(snapshot.getMessage());
			return;
		}
		dismissSteamAuthDialog();
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_guard_confirmation_title)
			.setMessage(snapshot.getMessage())
			.setCancelable(false)
			.setNegativeButton(R.string.steam_guard_cancel_login_button, (ignored, which) -> cancelSteamAuthentication())
			.setNeutralButton(R.string.steam_guard_confirmation_wait_button, (ignored, which) -> {
				suppressedConfirmationTransactionId = transactionId;
			})
			.setPositiveButton(R.string.steam_guard_confirmation_open_button, (ignored, which) -> {
				suppressedConfirmationTransactionId = transactionId;
				openSteamMobileApp();
			})
			.create();
		showSteamAuthDialog(dialog, key);
	}

	private void showSteamGuardCodeDialog(SteamAuthForegroundService.Snapshot snapshot) {
		SteamGuardChallengeType type = snapshot.getChallengeType();
		if (type == null) {
			showMessage(getString(R.string.steam_auth_error_unsupported_challenge));
			return;
		}
		String transactionId = snapshot.getTransactionId() == null ? "" : snapshot.getTransactionId();
		String key = "code:" + transactionId + ":" + type.name() + ":" + snapshot.getPreviousCodeRejected();
		if (steamAuthDialog != null && key.equals(steamAuthDialogKey)) {
			steamAuthDialog.setMessage(snapshot.getMessage());
			return;
		}
		dismissSteamAuthDialog();
		EditText input = new EditText(this);
		input.setSingleLine(true);
		input.setHint(R.string.steam_guard_code_hint);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.steam_guard_title)
			.setMessage(snapshot.getMessage())
			.setView(input)
			.setCancelable(false)
			.setNegativeButton(R.string.steam_guard_cancel_login_button, (ignored, which) -> cancelSteamAuthentication())
			.setPositiveButton(android.R.string.ok, null)
			.create();
		dialog.setOnShowListener(shown -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
			String code = input.getText() == null ? "" : input.getText().toString().trim();
			if (TextUtils.isEmpty(code)) {
				input.setError(getString(R.string.steam_guard_code_hint));
				return;
			}
			input.setText("");
			dialog.dismiss();
			if (steamAuthBinder != null) {
				steamAuthBinder.submitGuardCode(transactionId, type, code);
			} else {
				SteamAuthForegroundService.resumePending(this);
				bindSteamAuthService();
				showMessage(getString(R.string.steam_status_auth_resuming));
			}
		}));
		showSteamAuthDialog(dialog, key);
	}

	private void showSteamAuthDialog(AlertDialog dialog, String key) {
		steamAuthDialog = dialog;
		steamAuthDialogKey = key;
		dialog.setCanceledOnTouchOutside(false);
		dialog.setOnDismissListener(ignored -> {
			if (steamAuthDialog == dialog) {
				steamAuthDialog = null;
				steamAuthDialogKey = "";
			}
		});
		dialog.show();
	}

	private void dismissSteamAuthDialog() {
		AlertDialog dialog = steamAuthDialog;
		steamAuthDialog = null;
		steamAuthDialogKey = "";
		if (dialog != null && dialog.isShowing()) {
			dialog.dismiss();
		}
	}

	private void handleSteamAuthTerminalSnapshot(SteamAuthForegroundService.Snapshot snapshot) {
		dismissSteamAuthDialog();
		steamAuthActive = false;
		pendingAuthUsername = null;
		pendingAuthPassword = null;
		updateAuthActionButtons();
		if (lastHandledAuthTerminalRevision == snapshot.getRevision()) {
			return;
		}
		lastHandledAuthTerminalRevision = snapshot.getRevision();
		if (
			snapshot.getStage() == SteamAuthForegroundService.Stage.SUCCESS ||
			snapshot.getStage() == SteamAuthForegroundService.Stage.FAILED
		) {
			suppressedConfirmationTransactionId = "";
			refreshStatus();
		}
		showMessage(snapshot.getMessage());
	}

	private void cancelSteamAuthentication() {
		suppressedConfirmationTransactionId = "";
		if (steamAuthBinder != null) {
			steamAuthBinder.cancel();
		} else {
			SteamAuthForegroundService.cancelPending(this);
		}
	}

	private void openSteamMobileApp() {
		Intent launch = getPackageManager().getLaunchIntentForPackage(STEAM_ANDROID_PACKAGE);
		if (launch == null) {
			showMessage(getString(R.string.steam_auth_open_steam_unavailable));
			return;
		}
		launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		startActivity(launch);
	}

	private void updateAuthActionButtons() {
		boolean enabled = !steamAuthActive && !busy;
		if (loginButton != null) {
			loginButton.setEnabled(enabled);
		}
		if (verifyLoginButton != null) {
			verifyLoginButton.setEnabled(enabled);
		}
		if (logoutButton != null) {
			logoutButton.setEnabled(enabled);
		}
	}

	private void startPayloadDownload() {
		SteamAuthStore.AuthSnapshot snapshot = SteamAuthStore.readSnapshot(this);
		if (!snapshot.refreshTokenConfigured) {
			showMessage(getString(R.string.steam_download_login_required));
			showLoginDialog();
			return;
		}
		String branch = resolveSelectedBranch();
		if (TextUtils.isEmpty(branch)) {
			showMessage(getString(R.string.steam_branch_custom_required));
			return;
		}
		if (busy || steamAuthActive) {
			return;
		}
		busy = true;
		downloadingPayload = true;
		activeDownloadControl = new PayloadManager.ImportControl();
		if (downloadProgressPanel != null) {
			downloadProgressPanel.reset(branch);
			downloadProgressPanel.setCancelEnabled(true);
		}
		updateDownloadUiVisibility();
		setProgress(0, getString(R.string.steam_status_downloading_payload));
		final PayloadManager.ImportControl control = activeDownloadControl;
		new Thread(() -> {
			try {
				PayloadManager.Status status = new Sts2SteamPayloadDownloader(this).downloadAndInstall(branch, progress -> {
					runOnUiThread(() -> {
						if (downloadProgressPanel != null) {
							downloadProgressPanel.update(
								progress.getPhase(),
								progress.getPercent(),
								progress.getMessage(),
								progress.getDownloadedBytes(),
								progress.getTotalBytes()
							);
						}
						setProgress(progress.getPercent(), progress.getMessage());
					});
					return kotlin.Unit.INSTANCE;
				}, control);
				String result = getString(R.string.status_import_game_payload_done, status.shortVersionLabel());
				runOnUiThread(() -> {
					busy = false;
					downloadingPayload = false;
					activeDownloadControl = null;
					if (downloadProgressPanel != null) {
						downloadProgressPanel.stopAnimations();
					}
					updateDownloadUiVisibility();
					setProgress(100, result);
					refreshStatus();
					showMessage(result);
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					downloadingPayload = false;
					activeDownloadControl = null;
					if (downloadProgressPanel != null) {
						downloadProgressPanel.stopAnimations();
					}
					updateDownloadUiVisibility();
					String message;
					if (control != null && control.isCancelled()) {
						message = getString(R.string.steam_download_cancelled);
					} else {
						message = formatOperationError(exception);
					}
					refreshStatusOnly();
					showMessage(control != null && control.isCancelled()
						? message
						: getString(R.string.error_operation_failed) + ": " + message);
				});
			}
		}, "sts2-steam-payload-download").start();
	}

	private String resolveSelectedBranch() {
		if (selectedBranch == BRANCH_BETA) {
			return "public-beta";
		}
		if (selectedBranch == BRANCH_CUSTOM) {
			CharSequence value = customBranchInput == null ? null : customBranchInput.getText();
			return value == null ? "" : value.toString().trim();
		}
		return Sts2SteamPayloadDownloader.DEFAULT_BRANCH;
	}

	private void updateDownloadUiVisibility() {
		if (downloadSetupBlock != null) {
			downloadSetupBlock.setVisibility(downloadingPayload ? View.GONE : View.VISIBLE);
		}
		if (downloadProgressPanel != null) {
			downloadProgressPanel.getView().setVisibility(downloadingPayload ? View.VISIBLE : View.GONE);
		}
		if (downloadButton != null) {
			downloadButton.setEnabled(!busy && !steamAuthActive);
		}
		if (branchPublicCard != null) {
			branchPublicCard.setEnabled(!busy);
			branchBetaCard.setEnabled(!busy);
			branchCustomCard.setEnabled(!busy);
		}
	}

	private void confirmCloudOverwrite() {
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
		if (busy || steamAuthActive) {
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
					refreshStatusOnly();
					if (conflict != null) {
						showCloudConflictDialog(conflict);
					} else {
						String message = formatOperationError(exception);
						showMessage(getString(R.string.error_operation_failed) + ": " + message);
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
		if (busy || steamAuthActive) {
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
					String message = formatOperationError(exception);
					refreshStatusOnly();
					showMessage(getString(R.string.error_operation_failed) + ": " + message);
				});
			}
		}, "sts2-steam-operation").start();
	}

	private String formatOperationError(Throwable error) {
		String raw = rawErrorMessage(error);
		String readable = formatRawOperationError(raw);
		if (!readable.equals(raw)) {
			return readable;
		}
		if (containsCause(error, CancellationException.class) || raw.contains("CancellationException")) {
			return getString(R.string.steam_error_login_cancelled_or_interrupted);
		}
		if (containsCause(error, TimeoutException.class) || containsAny(raw, "timed out", "timeout")) {
			return getString(R.string.steam_error_timeout);
		}
		if (containsAny(raw, "websocket transport has a watchdog", "watchdog", "steam disconnected", "client or session is no longer active", "NoConnection", "ConnectFailed", "RemoteDisconnect")) {
			return getString(R.string.steam_error_connection_lost);
		}
		if (containsAny(raw, "Failed to resolve Steam websocket CM hostname", "no usable address", "no websocket CM candidate")) {
			return getString(R.string.steam_error_cm_unreachable);
		}
		AuthenticationException authError = findCause(error, AuthenticationException.class);
		if (authError != null && authError.getResult() != null) {
			String message = describeSteamAuthResult(authError.getResult());
			if (!TextUtils.isEmpty(message)) {
				return message;
			}
		}
		return raw.isEmpty() ? getString(R.string.steam_error_unknown) : raw;
	}

	private String formatStoredError(String raw) {
		if (TextUtils.isEmpty(raw)) {
			return "";
		}
		return formatRawOperationError(raw);
	}

	private String formatRawOperationError(String raw) {
		if (containsAny(raw, "InvalidPassword", "账号名或密码错误", "account name or password")) {
			return getString(R.string.steam_error_invalid_password);
		}
		if (containsAny(raw, "InvalidLoginAuthCode", "TwoFactorCodeMismatch", "ExpiredLoginAuthCode", "Steam Guard")) {
			return getString(R.string.steam_error_guard_code);
		}
		if (containsAny(raw, "RateLimitExceeded", "AccountLoginDeniedThrottle", "too many", "请求过于频繁")) {
			return getString(R.string.steam_error_rate_limited);
		}
		if (containsAny(raw, "ServiceUnavailable", "Busy", "TryAnotherCM", "RemoteCallFailed")) {
			return getString(R.string.steam_error_service_busy);
		}
		if (containsAny(raw, "CancellationException")) {
			return getString(R.string.steam_error_login_cancelled_or_interrupted);
		}
		if (containsAny(raw, "timed out", "timeout")) {
			return getString(R.string.steam_error_timeout);
		}
		if (containsAny(raw, "websocket transport has a watchdog", "watchdog", "steam disconnected", "client or session is no longer active", "NoConnection", "ConnectFailed", "RemoteDisconnect")) {
			return getString(R.string.steam_error_connection_lost);
		}
		if (containsAny(raw, "Failed to resolve Steam websocket CM hostname", "no usable address", "no websocket CM candidate")) {
			return getString(R.string.steam_error_cm_unreachable);
		}
		return raw == null ? "" : raw;
	}

	private String describeSteamAuthResult(EResult result) {
		switch (result) {
			case InvalidPassword:
			case AccountNotFound:
				return getString(R.string.steam_error_invalid_password);
			case InvalidLoginAuthCode:
			case TwoFactorCodeMismatch:
			case ExpiredLoginAuthCode:
				return getString(R.string.steam_error_guard_code);
			case AccountLogonDenied:
			case AccountLoginDeniedNeedTwoFactor:
				return getString(R.string.steam_error_guard_required);
			case AccountLoginDeniedThrottle:
			case RateLimitExceeded:
				return getString(R.string.steam_error_rate_limited);
			case Timeout:
				return getString(R.string.steam_error_timeout);
			case NoConnection:
			case ConnectFailed:
			case RemoteDisconnect:
				return getString(R.string.steam_error_connection_lost);
			case ServiceUnavailable:
			case Busy:
			case TryAnotherCM:
			case RemoteCallFailed:
				return getString(R.string.steam_error_service_busy);
			case Expired:
				return getString(R.string.steam_error_session_expired);
			default:
				return "";
		}
	}

	private static String rawErrorMessage(Throwable error) {
		if (error == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		Throwable current = error;
		int depth = 0;
		while (current != null && depth < 8) {
			if (builder.length() > 0) {
				builder.append(" | ");
			}
			builder.append(current.getClass().getSimpleName());
			String message = current.getMessage();
			if (!TextUtils.isEmpty(message)) {
				builder.append(": ").append(message.replace('\r', ' ').replace('\n', ' ').trim());
			}
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
			depth++;
		}
		return builder.toString().trim();
	}

	private static boolean containsAny(String value, String... needles) {
		String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
		for (String needle : needles) {
			if (needle != null && lower.contains(needle.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private static <T extends Throwable> boolean containsCause(Throwable error, Class<T> type) {
		return findCause(error, type) != null;
	}

	private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
		Throwable current = error;
		int depth = 0;
		while (current != null && depth < 16) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
			depth++;
		}
		return null;
	}

	private void showOperationDialog(String message) {
		if (downloadingPayload) {
			return;
		}
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
			if (operationDialog != null) {
				operationDialog.setProgress(percent, message);
			}
		});
	}

	private void refreshStatus() {
		refreshStatusOnly();
	}

	private void refreshStatusOnly() {
		SteamAuthStore.AuthSnapshot snapshot = SteamAuthStore.readSnapshot(this);
		boolean loggedIn = snapshot.refreshTokenConfigured;
		String account = loggedIn
			? (TextUtils.isEmpty(snapshot.accountName) ? getString(R.string.steam_account_title) : snapshot.accountName)
			: getString(R.string.steam_not_logged_in);
		String steamId = TextUtils.isEmpty(snapshot.steamId64) ? getString(R.string.unknown) : snapshot.steamId64;
		if (profileNameView != null) {
			profileNameView.setText(account);
		}
		if (profileIdView != null) {
			profileIdView.setText(getString(R.string.steam_center_steamid_format, steamId));
		}
		if (profileBadgeView != null) {
			profileBadgeView.setText(loggedIn ? R.string.steam_center_online_badge : R.string.steam_center_offline_badge);
			styleBadge(profileBadgeView, loggedIn);
		}
		if (accountProfileNameView != null) {
			accountProfileNameView.setText(account);
		}
		if (accountProfileIdView != null) {
			accountProfileIdView.setText(getString(R.string.steam_center_steamid_format, steamId));
		}
		if (accountProfileBadgeView != null) {
			accountProfileBadgeView.setText(loggedIn ? R.string.steam_center_online_badge : R.string.steam_center_offline_badge);
			styleBadge(accountProfileBadgeView, loggedIn);
		}
		if (accountUsernameValueView != null) {
			accountUsernameValueView.setText(account);
		}
		if (accountSteamIdValueView != null) {
			accountSteamIdValueView.setText(steamId);
		}
		if (tokenStatusView != null) {
			tokenStatusView.setText(loggedIn ? R.string.steam_account_token_status_ready : R.string.steam_account_token_status_empty);
		}
		if (accountLastErrorView != null && accountLastErrorRow != null) {
			String error = formatStoredError(snapshot.lastError);
			if (TextUtils.isEmpty(error)) {
				accountLastErrorRow.setVisibility(View.GONE);
				accountLastErrorView.setText("");
			} else {
				accountLastErrorRow.setVisibility(View.VISIBLE);
				accountLastErrorView.setText(error);
			}
		}
		updateAuthActionButtons();
		if (cloudStatusBodyView != null || cloudPathView != null || cloudModeValueView != null) {
			Sts2SteamCloudSyncManager.Status status = new Sts2SteamCloudSyncManager(this).getStatus();
			if (cloudStatusBodyView != null) {
				cloudStatusBodyView.setText(getString(
					R.string.steam_cloud_profile_status,
					status.profileId,
					status.remoteFileCount,
					status.hasBaseline ? getString(R.string.yes) : getString(R.string.no)
				));
			}
			if (cloudPathView != null) {
				cloudPathView.setText(status.accountRoot.getAbsolutePath());
			}
			if (cloudModeValueView != null) {
				cloudModeValueView.setText(cloudModeLabel(SteamSettings.getCloudMode(this)));
			}
			if (settingsSaveSwitch != null && settingsSaveSwitch.isChecked() != SteamSettings.shouldSyncSettingsSave(this)) {
				settingsSaveSwitch.setChecked(SteamSettings.shouldSyncSettingsSave(this));
			}
		}
	}

	private String cloudModeLabel(String mode) {
		if (SteamSettings.CLOUD_MODE_MANUAL.equals(mode)) {
			return getString(R.string.steam_cloud_mode_manual);
		}
		if (SteamSettings.CLOUD_MODE_PULL_ON_LAUNCH.equals(mode)) {
			return getString(R.string.steam_cloud_mode_pull_on_launch);
		}
		if (SteamSettings.CLOUD_MODE_FULL_AUTO.equals(mode)) {
			return getString(R.string.steam_cloud_mode_full_auto);
		}
		return getString(R.string.steam_cloud_mode_off);
	}

	private TextView createBadge(String text, boolean success) {
		TextView view = ExtraSettingsUi.caption(this, text);
		view.setTypeface(Typeface.DEFAULT_BOLD);
		view.setPadding(ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 3), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 3));
		styleBadge(view, success);
		return view;
	}

	private void styleBadge(TextView view, boolean success) {
		view.setTextColor(success ? ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(ExtraSettingsUi.dp(this, 8));
		if (success) {
			bg.setColor(Color.argb(180, 31, 79, 49));
			bg.setStroke(ExtraSettingsUi.dp(this, 1), Color.argb(120, 166, 211, 183));
		} else {
			bg.setColor(ExtraSettingsUi.COLOR_SECONDARY_CONTAINER);
			bg.setStroke(ExtraSettingsUi.dp(this, 1), ExtraSettingsUi.COLOR_OUTLINE);
		}
		view.setBackground(bg);
	}

	private void showMessage(String message) {
		View content = findViewById(android.R.id.content);
		if (content != null) {
			Snackbar.make(content, message, Snackbar.LENGTH_LONG).show();
		}
	}

	private LinearLayout.LayoutParams weighted(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(this, marginStartDp));
		return params;
	}

	private interface ThrowingSupplier { String run() throws Exception; }
	private interface CloudOperation { String run(Sts2SteamCloudSyncManager operation) throws Exception; }
}
