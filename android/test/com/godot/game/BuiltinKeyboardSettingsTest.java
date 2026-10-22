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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Persistence of the built-in keyboard switch inside settings.save. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class BuiltinKeyboardSettingsTest {
	private Context context;
	private ExtraSettingsRepository repository;

	@Before
	public void setUp() {
		context = RuntimeEnvironment.getApplication();
		context.getSharedPreferences("sts2_extra_settings", Context.MODE_PRIVATE).edit().clear().commit();
		context.getSharedPreferences("sts2_version_manager", Context.MODE_PRIVATE).edit().clear().commit();
		for (String directory : new String[] {"default", "instances", "payloads", "launcher"}) {
			deleteRecursively(new File(context.getFilesDir(), directory));
		}
		repository = new ExtraSettingsRepository(context);
	}

	@Test
	public void disabledByDefault() throws Exception {
		assertFalse(repository.isBuiltinFullKeyboardEnabled());
		assertFalse(repository.loadSettingsJson().getBoolean(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED));
	}

	@Test
	public void switchSurvivesReload() throws Exception {
		repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_BUILTIN_FULL_KEYBOARD, true));
		assertTrue(repository.loadSettingsJson().optBoolean(ExtraSettingsRepository.KEY_BUILTIN_FULL_KEYBOARD, false));
		assertTrue(ExtraSettingsRepository.isBuiltinKeyboardEnabled(context));
		repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_BUILTIN_FULL_KEYBOARD, false));
		assertFalse(ExtraSettingsRepository.isBuiltinKeyboardEnabled(context));
	}

	@Test
	public void floatingShortcutAndFullKeyboardAreIndependent() throws Exception {
		repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED, true));
		assertTrue(new ExtraSettingsRepository(context).loadSettingsJson()
			.getBoolean(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED));
		assertFalse(ExtraSettingsRepository.isBuiltinKeyboardEnabled(context));
		repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_BUILTIN_FULL_KEYBOARD, true));
		repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED, false));
		assertFalse(new ExtraSettingsRepository(context).loadSettingsJson()
			.getBoolean(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED));
		assertTrue(ExtraSettingsRepository.isBuiltinKeyboardEnabled(context));
	}

	@Test
	public void existingSettingsWithoutTheKeyGainTheDefault() throws Exception {
		JSONObject existing = new JSONObject().put("android_floating_mouse_enabled", true);
		repository.saveSettingsJson(existing);
		assertFalse(repository.loadSettingsJson().optBoolean(ExtraSettingsRepository.KEY_BUILTIN_FULL_KEYBOARD, false));
		assertFalse(repository.loadSettingsJson().getBoolean(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED));
		// Filling in derived settings must not disturb unrelated user values.
		assertTrue(repository.loadSettingsJson().optBoolean("android_floating_mouse_enabled", false));
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) deleteRecursively(child);
		}
		//noinspection ResultOfMethodCallIgnored
		file.delete();
	}
}
