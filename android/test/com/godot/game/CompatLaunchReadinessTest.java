package com.godot.game;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
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
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CompatLaunchReadinessTest {
	private Context context;
	private LaunchProfileManager profiles;
	private CompatPackManager packs;
	private ExtraSettingsRepository settings;
	private File selectedPack;
	private File stagedDll;
	private File stagedOverlay;

	@Before public void setup() throws Exception {
		context = RuntimeEnvironment.getApplication();
		profiles = new LaunchProfileManager(context);
		File game = profiles.getPayloadGameDir("fixture-payload");
		JSONObject release = new JSONObject().put("version", "fixture-version");
		put(new File(game, "SlayTheSpire2.pck"), "GDPCsynthetic-game");
		put(new File(game, "release_info.json"), release.toString());
		put(new File(game, ".payload_manifest.json"), new JSONObject()
			.put("identity", new JSONObject().put("release_info", release)).toString());
		put(new File(game, "data_sts2_windows_x86_64/sts2.dll"), "synthetic-game-assembly");
		put(new File(game, "data_sts2_windows_x86_64/sts2.deps.json"), "{}");
		put(new File(game, "data_sts2_windows_x86_64/sts2.runtimeconfig.json"), "{}");
		selectedPack = pack("fixture-selected", "Selected fixture pack");
		pack("fixture-working", "Working fixture pack");
		profiles.createProfile("fixture-payload", "Fixture", "global", "global", "fixture-selected", "", true);
		settings = new ExtraSettingsRepository(context);
		settings.saveSetting(json -> json.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, true));
		packs = new CompatPackManager(context);
		stagedDll = new File(context.getFilesDir(), ".godot/mono/publish/arm64/STS2Mobile.dll");
		stagedOverlay = new File(context.getFilesDir(), "port_compat.pck");
		put(stagedDll, "old-compat");
		put(stagedOverlay, "old-overlay");
		put(new File(stagedDll.getParentFile(), "sts2.dll"), "already-prepared-game");
	}

	@After public void closeDialogs() {
		Dialog dialog = ShadowDialog.getLatestDialog();
		if (dialog != null) dialog.dismiss();
	}

	@Test public void eitherMissingArtifactStopsPreparationBeforePartialStaging() throws Exception {
		for (String artifact : new String[] {"STS2Mobile.dll", "port_compat.pck"}) {
			File missing = new File(selectedPack, artifact);
			byte[] original = Files.readAllBytes(missing.toPath());
			Files.delete(missing.toPath());
			try {
				assertThrows(IOException.class,
					() -> new GameLaunchPreparationManager(context).prepareAssembliesAndOverlay());
				assertEquals("old-compat", text(stagedDll));
				assertEquals("old-overlay", text(stagedOverlay));
			} finally {
				Files.write(missing.toPath(), original);
			}
		}
	}

	@Test public void incompleteSelectionOffersRecommendationWithoutRebindingProfile() throws Exception {
		Files.delete(new File(selectedPack, "port_compat.pck").toPath());
		GameSettingsActivity activity = Robolectric.buildActivity(GameSettingsActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		set(activity, "repository", settings);
		set(activity, "payloadManager", new PayloadManager(context));
		set(activity, "compatPackManager", packs);
		set(activity, "launchProfileManager", profiles);
		set(activity, "bundledCompatPackBootstrapFinished", true);
		activity.launchGame();
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertTrue("Incomplete artifacts must use the missing-dependency flow, not mismatch override", dialog instanceof BottomSheetDialog);
		assertTrue(containsText(dialog.getWindow().getDecorView(), "Working fixture pack"));
		assertEquals("fixture-selected", profiles.getSelectedCompatPackId());
		assertNull(shadowOf(activity).getNextStartedActivity());
	}

	@Test public void preparedIntentCannotBypassAnIncompleteBoundPack() throws Exception {
		Files.delete(new File(selectedPack, "port_compat.pck").toPath());
		GodotApp activity = Robolectric.buildActivity(GodotApp.class).get();
		InvocationTargetException failure = assertThrows(InvocationTargetException.class,
			() -> ensurePrepared(activity));
		assertTrue(failure.getCause() instanceof IllegalStateException);
		assertEquals("old-compat", text(stagedDll));
		assertEquals("old-overlay", text(stagedOverlay));
	}

	@Test public void explicitDisabledCompatibilityStillRemovesBothStagedArtifacts() throws Exception {
		Files.delete(new File(selectedPack, "STS2Mobile.dll").toPath());
		settings.saveSetting(json -> json.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, false));
		GodotApp activity = Robolectric.buildActivity(GodotApp.class).get();
		ensurePrepared(activity);
		assertFalse(stagedDll.exists());
		assertFalse(stagedOverlay.exists());
		assertEquals("already-prepared-game", text(new File(stagedDll.getParentFile(), "sts2.dll")));
	}

	private File pack(String id, String name) throws Exception {
		File directory = new File(context.getFilesDir(), "compat-packs/" + id);
		JSONObject manifest = new JSONObject().put("schema", 1).put("pack_id", id)
			.put("display_name", name).put("compat_version", "fixture-1")
			.put("target_game", new JSONObject().put("version", "fixture-version"));
		put(new File(directory, "compat_manifest.json"), manifest.toString());
		put(new File(directory, "STS2Mobile.dll"), "synthetic-compat");
		put(new File(directory, "port_compat.pck"), "GDPCsynthetic-overlay");
		return directory;
	}

	private static void ensurePrepared(GodotApp activity) throws Exception {
		Method method = GodotApp.class.getDeclaredMethod("ensureLaunchPreparedBeforeGodot", boolean.class);
		method.setAccessible(true);
		method.invoke(activity, true);
	}

	private static void set(Object owner, String name, Object value) throws Exception {
		Field field = GameSettingsActivity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}

	private static boolean containsText(View view, String value) {
		if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return true;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int index = 0; index < group.getChildCount(); index++) {
				if (containsText(group.getChildAt(index), value)) return true;
			}
		}
		return false;
	}

	private static void put(File file, String content) throws Exception {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private static String text(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}
}
