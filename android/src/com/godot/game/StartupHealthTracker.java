package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

public final class StartupHealthTracker {
	private static final String PREFERENCES_NAME = "sts2_launch_health";
	private static final String KEY_PENDING_LAUNCH = "pending_launch";
	private static final String KEY_STARTED_AT_MS = "started_at_ms";
	private static final String KEY_STARTED_RENDERER = "started_renderer";

	private StartupHealthTracker() {
	}

	public static void markGameLaunchStarted(Context context) {
		SharedPreferences preferences = getPreferences(context);
		preferences.edit()
			.putBoolean(KEY_PENDING_LAUNCH, true)
			.putLong(KEY_STARTED_AT_MS, System.currentTimeMillis())
			.putString(KEY_STARTED_RENDERER, RendererPreference.getSelectedRenderer(context))
			.apply();
	}

	public static void markGameLaunchFinished(Context context) {
		clearPendingLaunchState(context);
	}

	public static void clearPendingLaunchState(Context context) {
		SharedPreferences preferences = getPreferences(context);
		preferences.edit()
			.putBoolean(KEY_PENDING_LAUNCH, false)
			.remove(KEY_STARTED_AT_MS)
			.remove(KEY_STARTED_RENDERER)
			.apply();
	}

	public static String consumePendingLaunchWarning(Context context) {
		SharedPreferences preferences = getPreferences(context);
		if (!preferences.getBoolean(KEY_PENDING_LAUNCH, false)) {
			return null;
		}

		String renderer = preferences.getString(KEY_STARTED_RENDERER, RendererPreference.getSelectedRenderer(context));
		clearPendingLaunchState(context);

		StringBuilder message = new StringBuilder(context.getString(R.string.status_previous_launch_incomplete));
		message.append(' ');
		if (RendererPreference.RENDERER_VULKAN.equals(renderer)) {
			message.append(context.getString(R.string.status_previous_launch_renderer_vulkan_hint));
		} else {
			message.append(context.getString(R.string.status_previous_launch_renderer_generic_hint, getRendererDisplayName(context, renderer)));
		}
		return message.toString();
	}

	public static String describePendingLaunch(Context context) {
		SharedPreferences preferences = getPreferences(context);
		boolean pendingLaunch = preferences.getBoolean(KEY_PENDING_LAUNCH, false);
		if (!pendingLaunch) {
			return "none";
		}
		long startedAtMs = preferences.getLong(KEY_STARTED_AT_MS, 0L);
		String renderer = preferences.getString(KEY_STARTED_RENDERER, RendererPreference.getSelectedRenderer(context));
		return "pending_launch=true, started_at_ms=" + startedAtMs + ", renderer=" + renderer;
	}

	private static SharedPreferences getPreferences(Context context) {
		return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
	}

	private static String getRendererDisplayName(Context context, String renderer) {
		if (RendererPreference.RENDERER_OPENGL_ES3.equals(renderer)) {
			return context.getString(R.string.renderer_option_opengl_es3);
		}
		return context.getString(R.string.renderer_option_vulkan);
	}
}
