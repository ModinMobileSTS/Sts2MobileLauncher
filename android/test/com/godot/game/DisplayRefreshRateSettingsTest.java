package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DisplayRefreshRateSettingsTest {
	private static final String LEGACY_KEY = "android_high_refresh_rate_enabled";
	private Context context;
	private ExtraSettingsRepository repository;
	private SharedPreferences preferences;

	@Before
	public void setUp() throws Exception {
		context = RuntimeEnvironment.getApplication();
		preferences = context.getSharedPreferences("sts2_extra_settings", Context.MODE_PRIVATE);
		preferences.edit().clear().commit();
		context.getSharedPreferences("sts2_version_manager", Context.MODE_PRIVATE).edit().clear().commit();
		deleteRecursively(new File(context.getFilesDir(), "default"));
		deleteRecursively(new File(context.getFilesDir(), "instances"));
		deleteRecursively(new File(context.getFilesDir(), "payloads"));
		deleteRecursively(new File(context.getFilesDir(), "launcher"));
		repository = new ExtraSettingsRepository(context);
	}

	@Test
	public void legacyJsonFalseMigratesToSystemAndRemovesLegacyInputs() throws Exception {
		preferences.edit().putBoolean(LEGACY_KEY, true).commit();
		repository.saveSettingsJson(new JSONObject()
			.put("schema_version", 5)
			.put(LEGACY_KEY, false));

		JSONObject loaded = repository.loadSettingsJson();

		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM,
			repository.getDisplayRefreshRateMode(loaded));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM,
			loaded.getString(ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE));
		assertFalse(loaded.has(LEGACY_KEY));
		assertFalse(preferences.contains(LEGACY_KEY));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM,
			preferences.getString("android_display_refresh_rate_mode", null));
		JSONObject persisted = readSettings(repository.getSettingsFile());
		assertFalse(persisted.has(LEGACY_KEY));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM,
			persisted.getString(ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE));
	}

	@Test
	public void legacySharedPreferenceValuesMigrateToModes() {
		for (boolean enabled : new boolean[] {false, true}) {
			preferences.edit().clear().putBoolean(LEGACY_KEY, enabled).commit();
			String expected = enabled
				? ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH
				: ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM;

			assertEquals(expected, ExtraSettingsPreferences.getDisplayRefreshRateMode(context));
			assertFalse(preferences.contains(LEGACY_KEY));
			assertEquals(expected, preferences.getString("android_display_refresh_rate_mode", null));
		}
	}

	@Test
	public void newModeWinsOverLegacyJsonAndPreference() throws Exception {
		preferences.edit().putBoolean(LEGACY_KEY, true).commit();
		repository.saveSettingsJson(new JSONObject()
			.put(LEGACY_KEY, true)
			.put(ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE,
				ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM));

		JSONObject loaded = repository.loadSettingsJson();

		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM,
			repository.getDisplayRefreshRateMode(loaded));
		assertFalse(loaded.has(LEGACY_KEY));
		assertFalse(preferences.contains(LEGACY_KEY));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM,
			preferences.getString("android_display_refresh_rate_mode", null));
	}

	@Test
	public void invalidNewModeIsPersistedAsHigh() throws Exception {
		repository.saveSettingsJson(new JSONObject().put(
			ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE, "144hz"));

		JSONObject loaded = repository.loadSettingsJson();

		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH,
			repository.getDisplayRefreshRateMode(loaded));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH,
			loaded.getString(ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH,
			readSettings(repository.getSettingsFile()).getString(
				ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE));
	}

	@Test
	public void gameRewriteWithoutModeBackfillsPersisted60Hz() throws Exception {
		repository.saveSettingsJson(new JSONObject().put(
			ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE,
			ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH));
		repository.saveDisplayRefreshRateMode(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_60HZ);
		repository.saveSettingsJson(new JSONObject().put("schema_version", 6).put("fullscreen", true));

		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_60HZ,
			repository.getDisplayRefreshRateModeForLaunch());
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_60HZ,
			readSettings(repository.getSettingsFile()).getString(
				ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_60HZ,
			ExtraSettingsPreferences.getDisplayRefreshRateMode(context));
	}

	@Test
	public void switchingProfilesRestoresEachProfileExplicitMode() throws Exception {
		LaunchProfileManager profiles = new LaunchProfileManager(context);
		File game = profiles.getPayloadGameDir("refresh-fixture");
		Files.createDirectories(game.toPath());
		Files.write(new File(game, "SlayTheSpire2.pck").toPath(), "pck".getBytes(StandardCharsets.UTF_8));
		File data = new File(game, "data_sts2_windows_x86_64");
		Files.createDirectories(data.toPath());
		Files.write(new File(data, "sts2.dll").toPath(), "dll".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(data, "sts2.deps.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(data, "sts2.runtimeconfig.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(game, ".payload_manifest.json").toPath(), new JSONObject()
			.put("identity", new JSONObject().put("release_info", new JSONObject().put("version", "fixture")))
			.toString().getBytes(StandardCharsets.UTF_8));

		LaunchProfileManager.LaunchProfile first = profiles.createProfile(
			"refresh-fixture", "First", LaunchProfileManager.SAVE_MODE_ISOLATED,
			LaunchProfileManager.MODS_MODE_GLOBAL, "", "", true);
		LaunchProfileManager.LaunchProfile second = profiles.createProfile(
			"refresh-fixture", "Second", LaunchProfileManager.SAVE_MODE_ISOLATED,
			LaunchProfileManager.MODS_MODE_GLOBAL, "", "", false);

		profiles.selectProfile(first.id);
		repository.saveDisplayRefreshRateMode(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_60HZ);
		profiles.selectProfile(second.id);
		repository.saveDisplayRefreshRateMode(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH);

		profiles.selectProfile(first.id);
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_60HZ,
			repository.getDisplayRefreshRateModeForLaunch());
		profiles.selectProfile(second.id);
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH,
			repository.getDisplayRefreshRateModeForLaunch());
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_60HZ,
			readSettings(new File(first.dir, "default/1/settings.save")).getString(
				ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE));
		assertEquals(ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH,
			readSettings(new File(second.dir, "default/1/settings.save")).getString(
				ExtraSettingsRepository.KEY_DISPLAY_REFRESH_RATE_MODE));
	}

	private static JSONObject readSettings(File file) throws Exception {
		return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	private static void deleteRecursively(File file) throws Exception {
		if (!file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		assertTrue("Unable to remove test fixture: " + file, file.delete() || !file.exists());
	}
}
