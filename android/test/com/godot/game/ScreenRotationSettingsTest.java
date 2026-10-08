package com.godot.game;

import android.content.Context;

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

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ScreenRotationSettingsTest {
	private Context context;
	private ExtraSettingsRepository repository;

	@Before
	public void setUp() throws Exception {
		context = RuntimeEnvironment.getApplication();
		context.getSharedPreferences("sts2_extra_settings", Context.MODE_PRIVATE).edit().clear().commit();
		context.getSharedPreferences("sts2_version_manager", Context.MODE_PRIVATE).edit().clear().commit();
		for (String directory : new String[] {"default", "instances", "payloads", "launcher"}) {
			deleteRecursively(new File(context.getFilesDir(), directory));
		}
		repository = new ExtraSettingsRepository(context);
	}

	@Test
	public void portraitSelectionSurvivesReloadAndOverridesLegacyReverseFlag() throws Exception {
		repository.saveSettingsJson(new JSONObject()
			.put(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE, ExtraSettingsRepository.SCREEN_ROTATION_PORTRAIT)
			.put("android_flip_screen_180", true));

		JSONObject reloaded = new ExtraSettingsRepository(context).loadSettingsJson();
		JSONObject persisted = readSettings();
		assertEquals(ExtraSettingsRepository.SCREEN_ROTATION_PORTRAIT,
			reloaded.getString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE));
		assertEquals(ExtraSettingsRepository.SCREEN_ROTATION_PORTRAIT,
			persisted.getString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE));
		assertFalse(reloaded.getBoolean("android_flip_screen_180"));
		assertFalse(persisted.getBoolean("android_flip_screen_180"));
	}

	@Test
	public void freshSettingsDoNotEnablePortraitMode() throws Exception {
		JSONObject settings = repository.loadSettingsJson();
		assertEquals(ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE,
			settings.getString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE));
		assertEquals(ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE,
			readSettings().getString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE));
	}

	@Test
	public void leavingPortraitRestoresLandscapeWithoutStaleReverseFlag() throws Exception {
		repository.saveSettingsJson(new JSONObject()
			.put("android_flip_screen_180", true));
		assertEquals(ExtraSettingsRepository.SCREEN_ROTATION_REVERSE_LANDSCAPE,
			repository.loadSettingsJson().getString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE));

		repository.saveSetting(settings -> settings.put(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE,
			ExtraSettingsRepository.SCREEN_ROTATION_PORTRAIT));
		repository.saveSetting(settings -> settings.put(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE,
			ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE));

		JSONObject persisted = readSettings();
		assertEquals(ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE,
			persisted.getString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE));
		assertFalse(persisted.getBoolean("android_flip_screen_180"));
	}

	private JSONObject readSettings() throws Exception {
		return new JSONObject(new String(Files.readAllBytes(repository.getSettingsFile().toPath()),
			StandardCharsets.UTF_8));
	}

	private static void deleteRecursively(File file) throws Exception {
		if (!file.exists()) return;
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) deleteRecursively(child);
		}
		Files.delete(file.toPath());
	}
}
