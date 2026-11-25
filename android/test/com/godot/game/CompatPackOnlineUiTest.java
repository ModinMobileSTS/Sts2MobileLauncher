package com.godot.game;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.godot.game.CompatPackReleaseSecurityTest.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CompatPackOnlineUiTest {
	private static final String PAYLOAD_ID = "launch-dialog-payload";
	private static final String OFFLINE_ID = "sts2-android-offline-bootstrap";
	private static final String OFFLINE_TARGET = "offline-any";
	private static final byte[] ZIP_PLACEHOLDER = {1};
	private Context context;
	private GameSettingsActivity activity;
	private LaunchProfileManager profiles;
	private SigningFixture signing;
	private StubTransport transport;
	private CompatPackReleaseRepository repository;

	@Before public void setUp() throws Exception {
		context = RuntimeEnvironment.getApplication();
		profiles = new LaunchProfileManager(context);
		signing = new SigningFixture();
		transport = new StubTransport();
		repository = transport.repository(context, signing);
		ExtraSettingsPreferences.setFirstRunSetupCompleted(context, true);
		ExtraSettingsRepository settings = new ExtraSettingsRepository(context);
		settings.saveSetting(json -> json.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, true));

		// Use the real launch and dialog paths without starting unrelated onCreate workers.
		activity = Robolectric.buildActivity(GameSettingsActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		field("repository").set(activity, settings);
		field("payloadManager").set(activity, new PayloadManager(context));
		field("compatPackManager").set(activity, new CompatPackManager(context));
		field("launchProfileManager").set(activity, profiles);
		field("compatReleaseRepository").set(activity, repository);
		field("bundledCompatPackBootstrapFinished").setBoolean(activity, true);
		field("resumed").setBoolean(activity, true);
	}

	@After public void closeDialog() {
		Dialog dialog = ShadowDialog.getLatestDialog();
		if (dialog != null) dialog.dismiss();
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test public void missingPackSheetRetriesStartupNetworkFailureDespiteSilentCooldown() throws Exception {
		createProfile(false);
		transport.add((request, call) -> { throw new IOException("Network unavailable on startup"); });
		startSilentCheck();
		assertEquals(R.string.compat_online_check_failed, field("compatReleaseCheckMessage").getInt(activity));
		assertEquals(1, transport.requests.size());
		queueIndex(release("0.8.0", 800, 1, ZIP_PLACEHOLDER), "\"new\"");

		Dialog sheet = openLaunchDialog(false);
		assertTrue(hasText(sheet, R.string.compat_online_checking));
		awaitCheck();
		assertTrue(hasText(sheet.getWindow().getDecorView(), activity.getString(R.string.compat_online_download_launch,
			"0.8.0", Formatter.formatFileSize(activity, ZIP_PLACEHOLDER.length))));
		assertEquals(3, transport.requests.size());
		assertEquals(CompatPackReleaseTrust.SIGNATURE_URL, transport.requests.get(2).url().toString());
		assertEquals("", profiles.getSelectedCompatPackId());
	}

	@Test public void offlineDialogRevalidatesNonMatchingCacheButDoesNotRefreshOnEveryOpen() throws Exception {
		createProfile(true);
		String etag = "\"old\"";
		queueIndex(release("0.8.0", 800, 1, ZIP_PLACEHOLDER).put("targets", new JSONArray()
			.put(target("other", "v1.2.3", "v1.2.3", null))), etag);
		assertNotNull(repository.fetchIndexIfNeeded(true));
		long cachedAt = transport.now;
		transport.now += TimeUnit.HOURS.toSeconds(2);
		startSilentCheck();
		assertEquals(cachedAt, field("compatReleaseLastChecked").getLong(activity));
		assertEquals(2, transport.requests.size());

		transport.reply(304, new byte[0]);
		Dialog warning = openLaunchDialog(true);
		assertTrue(hasText(warning, R.string.compat_online_checking));
		awaitCheck();
		assertEquals(3, transport.requests.size());
		assertEquals(etag, transport.requests.get(2).header("If-None-Match"));
		warning.dismiss();

		ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
		warning = openLaunchDialog(true);
		assertNoNewCheck(warning, 3);
		warning.dismiss();

		ShadowSystemClock.advanceBy(Duration.ofSeconds(45));
		queueIndex(release("0.9.0", 900, 1, ZIP_PLACEHOLDER), "\"new\"");
		warning = openLaunchDialog(true);
		assertTrue(hasText(warning, R.string.compat_online_checking));
		awaitCheck();
		String switchLabel = activity.getString(R.string.compat_online_switch_launch, "0.9.0", GAME_VERSION);
		assertTrue(hasText(warning.getWindow().getDecorView(), switchLabel));
		assertEquals(5, transport.requests.size());
		warning.dismiss();

		ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
		warning = openLaunchDialog(true);
		assertNoNewCheck(warning, 5);
		assertTrue(hasText(warning.getWindow().getDecorView(), switchLabel));
		assertEquals(OFFLINE_ID, profiles.getSelectedCompatPackId());
	}

	@Test public void missingPackSheetDoesNotRetryWhenTrustIsUnconfigured() throws Exception {
		createProfile(false);
		field("compatReleaseRepository").set(activity, new CompatPackReleaseRepository(context, transport.client,
			new CompatPackReleaseVerifier(Collections.emptyMap()), () -> transport.now));
		startSilentCheck();
		assertEquals(R.string.compat_online_trust_unconfigured, field("compatReleaseCheckMessage").getInt(activity));
		ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
		assertNoNewCheck(openLaunchDialog(false), 0);
	}

	@Test public void compatTabRendersLocalActionsWhenIndexIsAvailableButRepositoryIsNot() throws Exception {
		CompatPackReleaseIndex index = signing.verify(release("0.8.0", 800, 1, ZIP_PLACEHOLDER));
		GameVersionManagerPage.selectCompatTab();
		try {
			View page = new GameVersionManagerPage(activity, activity, null, () -> index).build();
			assertTrue(hasText(page, activity.getString(R.string.version_manager_install_bundled_short)));
			assertTrue(hasText(page, activity.getString(R.string.version_manager_no_compat_packs)));
		} finally {
			GameVersionManagerPage.selectProfilesTab();
		}
	}

	private void createProfile(boolean offline) throws Exception {
		File game = profiles.getPayloadGameDir(PAYLOAD_ID);
		JSONObject manifest = payloadManifest();
		put(game, "SlayTheSpire2.pck", "GDPCsynthetic-game");
		put(game, "release_info.json", manifest.getJSONObject("identity").getJSONObject("release_info").toString());
		put(game, ".payload_manifest.json", manifest.toString());
		put(game, "data_sts2_windows_x86_64/sts2.dll", "synthetic-game");
		put(game, "data_sts2_windows_x86_64/sts2.deps.json", "{}");
		put(game, "data_sts2_windows_x86_64/sts2.runtimeconfig.json", "{}");
		if (offline) {
			File pack = new File(context.getFilesDir(), "compat-packs/" + OFFLINE_ID);
			put(pack, "compat_manifest.json", new JSONObject().put("schema", 2).put("pack_id", OFFLINE_ID)
				.put("compat_version", "0.2.0-dev").put("pack_kind", "offline-bootstrap").put("targets", new JSONArray()
				.put(new JSONObject().put("target_id", OFFLINE_TARGET).put("match_mode", "offline-wildcard")
					.put("versions", new JSONArray().put("*")))).toString());
			put(pack, "variants/" + OFFLINE_TARGET + "/STS2Mobile.dll", "synthetic-offline");
			put(pack, "variants/" + OFFLINE_TARGET + "/port_compat.pck", "GDPCsynthetic-overlay");
		}
		profiles.createProfile(PAYLOAD_ID, "Fixture", "global", "global", offline ? OFFLINE_ID : "",
			offline ? OFFLINE_TARGET : "", LaunchProfileManager.COMPAT_SELECTION_MANUAL, true);
	}

	private void queueIndex(JSONObject release, String etag) throws Exception {
		byte[] index = indexBytes(release);
		transport.add((request, call) -> response(request, 200, index).header("ETag", etag).build());
		transport.reply(200, signing.sign(index));
	}

	private void startSilentCheck() throws Exception {
		Method startup = GameSettingsActivity.class.getDeclaredMethod("maybeCheckCompatOnStartup");
		startup.setAccessible(true);
		startup.invoke(activity);
		assertTrue(field("compatReleaseChecking").getBoolean(activity));
		awaitCheck();
	}

	private Dialog openLaunchDialog(boolean offline) {
		activity.launchGame();
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertTrue(offline ? dialog instanceof AlertDialog : dialog instanceof BottomSheetDialog);
		assertTrue(hasText(dialog, offline ? R.string.launch_offline_bootstrap_confirm : R.string.launch_compat_recommendation_sheet_title));
		return dialog;
	}

	private void awaitCheck() throws Exception {
		Field checking = field("compatReleaseChecking");
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (checking.getBoolean(activity) && System.nanoTime() < deadline) {
			shadowOf(Looper.getMainLooper()).idle();
			Thread.sleep(5);
		}
		assertFalse("Timed out waiting for compat check callback", checking.getBoolean(activity));
	}

	private void assertNoNewCheck(Dialog dialog, int requests) throws Exception {
		assertFalse(hasText(dialog, R.string.compat_online_checking));
		assertFalse(field("compatReleaseChecking").getBoolean(activity));
		assertEquals(requests, transport.requests.size());
	}

	private boolean hasText(Dialog dialog, int resource) {
		return hasText(dialog.getWindow().getDecorView(), activity.getString(resource));
	}

	private static boolean hasText(View view, String label) {
		if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return true;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				if (hasText(group.getChildAt(i), label)) return true;
			}
		}
		return false;
	}

	private static Field field(String name) throws Exception {
		Field field = GameSettingsActivity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static void put(File root, String name, String content) throws IOException {
		File file = new File(root, name);
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), utf8(content));
	}
}
