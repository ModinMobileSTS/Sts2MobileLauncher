package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

public final class RendererPreference {
	public static final String RENDERER_VULKAN = "vulkan";
	public static final String RENDERER_OPENGL_ES3 = "opengl_es3";

	private static final String PREFERENCES_NAME = "sts2_extra_settings";
	private static final String KEY_RENDERER = "preferred_renderer";

	private RendererPreference() {
	}

	public static String getSelectedRenderer(Context context) {
		SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
		return normalizeRenderer(preferences.getString(KEY_RENDERER, RENDERER_OPENGL_ES3));
	}

	public static void setSelectedRenderer(Context context, String renderer) {
		SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
		preferences.edit().putString(KEY_RENDERER, normalizeRenderer(renderer)).apply();
	}

	public static String[] buildGodotCommandLineArgs(Context context) {
		return buildGodotCommandLineArgs(getSelectedRenderer(context));
	}

	public static String[] buildGodotCommandLineArgs(String renderer) {
		if (RENDERER_OPENGL_ES3.equals(normalizeRenderer(renderer))) {
			return new String[] { "--rendering-method", "gl_compatibility" };
		}
		return new String[0];
	}

	private static String normalizeRenderer(String renderer) {
		if (renderer == null) {
			return RENDERER_OPENGL_ES3;
		}
		String normalized = renderer.trim().toLowerCase(Locale.ROOT);
		if (RENDERER_VULKAN.equals(normalized)) {
			return RENDERER_VULKAN;
		}
		return RENDERER_OPENGL_ES3;
	}
}
