package com.godot.game;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DebugAutomationReceiver extends BroadcastReceiver {
	private static final String TAG = "Sts2Automation";

	@Override
	public void onReceive(Context context, Intent intent) {
		Context appContext = context.getApplicationContext();
		String runId = DebugAutomationRunner.normalizeRunId(DebugAutomationRunner.extra(intent, "run_id"));
		if (!DebugAutomationRunner.isAuthorized(appContext, intent)) {
			Log.w(TAG, "Rejected adb automation request; missing or invalid token. run_id=" + runId);
			DebugAutomationRunner.writeRejected(appContext, intent, runId, "unauthorized");
			setResultCode(1);
			setResultData("unauthorized");
			return;
		}
		Intent activityIntent = new Intent(appContext, DebugAutomationActivity.class);
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		Bundle extras = intent == null ? null : intent.getExtras();
		if (extras != null) {
			activityIntent.putExtras(extras);
		}
		try {
			appContext.startActivity(activityIntent);
			setResultCode(0);
			setResultData("accepted " + runId);
		} catch (Exception exception) {
			Log.w(TAG, "Unable to start adb automation activity.", exception);
			DebugAutomationRunner.writeRejected(appContext, intent, runId, exception.toString());
			setResultCode(2);
			setResultData(exception.toString());
		}
	}
}

final class DebugAutomationRunner {
	private static final String TAG = "Sts2Automation";
	private static final String TOKEN_RELATIVE_PATH = "automation/token.txt";
	private static final String LAST_RESULT_RELATIVE_PATH = "automation/last_result.json";
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Sts2DebugAutomation");
		thread.setDaemon(true);
		return thread;
	});

	private final Context context;
	private final Intent intent;
	private final String runId;
	private final File runDir;
	private final JSONArray events = new JSONArray();
	private final JSONObject result = new JSONObject();
	private final Runnable onComplete;
	private long startedAtMillis;

	private DebugAutomationRunner(Context context, Intent intent, String runId, Runnable onComplete) {
		this.context = context.getApplicationContext();
		this.intent = intent;
		this.runId = runId;
		this.runDir = new File(context.getFilesDir(), "automation/runs/" + runId);
		this.onComplete = onComplete;
	}

	static void enqueue(Context context, Intent intent, String runId) {
		enqueue(context, intent, runId, null);
	}

	static void enqueue(Context context, Intent intent, String runId, Runnable onComplete) {
		DebugAutomationRunner runner = new DebugAutomationRunner(context, intent, normalizeRunId(runId), onComplete);
		try {
			FileBrowserSupport.ensureDirectory(runner.runDir);
			runner.writeRequestSnapshot("accepted");
		} catch (Exception exception) {
			Log.w(TAG, "Unable to write adb automation accepted snapshot.", exception);
		}
		EXECUTOR.execute(runner::run);
	}

	static void writeRejected(Context context, Intent intent, String runId, String message) {
		try {
			String normalizedRunId = normalizeRunId(runId);
			File runDir = new File(context.getFilesDir(), "automation/runs/" + normalizedRunId);
			FileBrowserSupport.ensureDirectory(runDir);
			JSONObject result = new JSONObject();
			result.put("schema", 1);
			result.put("run_id", normalizedRunId);
			result.put("status", "failed");
			result.put("error", message == null ? "rejected" : message);
			result.put("ended_at_unix", System.currentTimeMillis() / 1000L);
			result.put("extras", sanitizedExtras(intent));
			String text = result.toString(2);
			FileBrowserSupport.writeTextFile(new File(runDir, "result.json"), text);
			FileBrowserSupport.writeTextFile(new File(context.getFilesDir(), LAST_RESULT_RELATIVE_PATH), text);
		} catch (Exception exception) {
			Log.w(TAG, "Unable to write rejected adb automation result.", exception);
		}
	}

	static boolean isAuthorized(Context context, Intent intent) {
		String provided = extra(intent, "token");
		if (TextUtils.isEmpty(provided)) {
			return false;
		}
		File tokenFile = new File(context.getFilesDir(), TOKEN_RELATIVE_PATH);
		if (!tokenFile.isFile()) {
			return false;
		}
		try {
			String expected = FileBrowserSupport.readTextFile(tokenFile).trim();
			return !TextUtils.isEmpty(expected) && expected.equals(provided.trim());
		} catch (Exception exception) {
			Log.w(TAG, "Unable to read adb automation token.", exception);
			return false;
		}
	}

	static String normalizeRunId(String rawRunId) {
		String value = rawRunId == null ? "" : rawRunId.trim();
		if (value.isEmpty()) {
			value = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
		}
		value = value.replaceAll("[^A-Za-z0-9._-]+", "-");
		while (value.startsWith("-") || value.startsWith(".")) {
			value = value.substring(1);
		}
		while (value.endsWith("-") || value.endsWith(".")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.isEmpty()) {
			value = "run-" + System.currentTimeMillis();
		}
		if (value.length() > 96) {
			value = value.substring(0, 96);
		}
		return value;
	}

	static String extra(Intent intent, String key) {
		if (intent == null || key == null) {
			return "";
		}
		Bundle extras = intent.getExtras();
		if (extras == null || !extras.containsKey(key)) {
			return "";
		}
		Object value = extras.get(key);
		return value == null ? "" : String.valueOf(value);
	}

	private void run() {
		startedAtMillis = System.currentTimeMillis();
		String command = normalizedExtra("command", "run");
		try {
			FileBrowserSupport.ensureDirectory(runDir);
			result.put("schema", 1);
			result.put("run_id", runId);
			result.put("command", command);
			result.put("status", "running");
			result.put("started_at_unix", startedAtMillis / 1000L);
			result.put("package", context.getPackageName());
			result.put("version_name", BuildConfig.VERSION_NAME);
			result.put("version_code", BuildConfig.VERSION_CODE);
			result.put("build_type", BuildConfig.BUILD_TYPE);
			result.put("extras", sanitizedExtras());
			writeResult();

			JSONObject details = dispatch(command);
			result.put("status", "succeeded");
			result.put("details", details);
		} catch (Throwable throwable) {
			Log.e(TAG, "ADB automation run failed: " + runId, throwable);
			putEvent("failed", messageFor(throwable));
			try {
				result.put("status", "failed");
				result.put("error", messageFor(throwable));
			} catch (Exception ignored) {
			}
		} finally {
			try {
				long elapsedMs = System.currentTimeMillis() - startedAtMillis;
				result.put("ended_at_unix", System.currentTimeMillis() / 1000L);
				result.put("elapsed_ms", elapsedMs);
				result.put("events", events);
				writeResult();
			} catch (Exception exception) {
				Log.w(TAG, "Unable to finalize adb automation result.", exception);
			}
			if (onComplete != null) {
				try {
					onComplete.run();
				} catch (Exception exception) {
					Log.w(TAG, "Unable to run adb automation completion callback.", exception);
				}
			}
		}
	}

	private JSONObject dispatch(String command) throws Exception {
		String normalized = command == null ? "run" : command.trim().toLowerCase(Locale.ROOT);
		if ("status".equals(normalized)) {
			return buildStatus();
		}
		if ("configure".equals(normalized)) {
			configureFromExtras();
			return buildStatus();
		}
		if ("clear".equals(normalized)) {
			JSONObject details = new JSONObject();
			clearRequestedState(details);
			details.put("status", buildStatus());
			return details;
		}
		if ("prepare".equals(normalized)) {
			configureFromExtras();
			JSONObject details = new JSONObject();
			clearRequestedState(details);
			prepareLaunch(details);
			details.put("status", buildStatus());
			return details;
		}
		if ("launch".equals(normalized)) {
			configureFromExtras();
			JSONObject details = new JSONObject();
			clearRequestedState(details);
			if (booleanExtra("prepare", true)) {
				prepareLaunch(details);
			}
			startGameActivity(details, booleanExtra("prepare", true));
			details.put("status", buildStatus());
			return details;
		}
		if ("open_settings".equals(normalized) || "settings".equals(normalized)) {
			configureFromExtras();
			JSONObject details = new JSONObject();
			startSettingsActivity(details);
			details.put("status", buildStatus());
			return details;
		}
		if ("workshop_diagnostics".equals(normalized) || "workshop".equals(normalized)) {
			return runWorkshopDiagnostics();
		}
		return runScenario();
	}

	private JSONObject runWorkshopDiagnostics() throws Exception {
		JSONObject details = new JSONObject();
		String query = normalizedExtra("query", "");
		int page = intExtra("page", 1);
		int pageSize = intExtra("page_size", 30);
		long started = SystemClock.uptimeMillis();
		putEvent("workshop_diagnostics_begin", "query=" + query + " page=" + page + " pageSize=" + pageSize);
		JSONObject diagnostics = new SteamWorkshopCatalog(context).runDiagnostics(query, page, pageSize);
		details.put("workshop", diagnostics);
		details.put("elapsed_ms", SystemClock.uptimeMillis() - started);
		putEvent("workshop_diagnostics_end", "elapsed=" + (SystemClock.uptimeMillis() - started) + "ms");
		return details;
	}

	private JSONObject runScenario() throws Exception {
		JSONObject details = new JSONObject();
		configureFromExtras();
		clearRequestedState(details);
		importPayloadIfRequested(details);
		selectAndUpdateProfile(details);
		importCompatIfRequested(details);
		selectCompatIfRequested(details);
		importModsIfRequested(details);
		applyModSelectionIfRequested(details);
		if (booleanExtra("prepare", booleanExtra("launch", false))) {
			prepareLaunch(details);
		}
		if (booleanExtra("open_settings", false)) {
			startSettingsActivity(details);
		}
		if (booleanExtra("launch", false)) {
			startGameActivity(details, booleanExtra("prepare", true));
		}
		details.put("status", buildStatus());
		return details;
	}

	private void configureFromExtras() throws Exception {
		ExtraSettingsRepository repository = new ExtraSettingsRepository(context);
		new LaunchProfileManager(context).bootstrapIfNeeded();
		String mode = normalizedExtra("mode", "");
		if (!mode.isEmpty()) {
			applyModeDefaults(repository, mode);
		}
		if (hasExtra("first_run_completed")) {
			ExtraSettingsPreferences.setFirstRunSetupCompleted(context, booleanExtra("first_run_completed", true));
			putEvent("first_run_completed", String.valueOf(booleanExtra("first_run_completed", true)));
		} else if (booleanExtra("complete_first_run", true) && (booleanExtra("launch", false) || booleanExtra("prepare", false))) {
			ExtraSettingsPreferences.setFirstRunSetupCompleted(context, true);
		}
		if (hasExtra("update_check_enabled")) {
			ExtraSettingsPreferences.setUpdateCheckEnabled(context, booleanExtra("update_check_enabled", false));
		} else if (booleanExtra("disable_update_check", true)) {
			ExtraSettingsPreferences.setUpdateCheckEnabled(context, false);
		}
		String startupBehavior = normalizedExtra("launcher_startup_behavior", "");
		if (!startupBehavior.isEmpty()) {
			ExtraSettingsPreferences.setLauncherStartupBehavior(context, startupBehavior);
		}
		String renderer = normalizedExtra("renderer", "");
		if (!renderer.isEmpty()) {
			RendererPreference.setSelectedRenderer(context, renderer);
			putEvent("renderer", RendererPreference.getSelectedRenderer(context));
		}
		String logLevel = normalizedExtra("log_level", "");
		if (!logLevel.isEmpty()) {
			repository.saveLogLevel(logLevel);
			putEvent("log_level", ExtraSettingsRepository.normalizeLogLevel(logLevel));
		}
		if (hasExtra("performance_overlay")) {
			repository.savePerformanceOverlayEnabled(booleanExtra("performance_overlay", false));
			putEvent("performance_overlay", String.valueOf(booleanExtra("performance_overlay", false)));
		}
		if (hasExtra("high_refresh") || hasExtra("high_refresh_rate")) {
			boolean enabled = hasExtra("high_refresh") ? booleanExtra("high_refresh", true) : booleanExtra("high_refresh_rate", true);
			repository.saveHighRefreshRateEnabled(enabled);
			putEvent("high_refresh", String.valueOf(enabled));
		}
		if (hasExtra("compat_enabled")) {
			boolean enabled = booleanExtra("compat_enabled", true);
			repository.saveSetting(settings -> settings.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, enabled));
			putEvent("compat_enabled", String.valueOf(enabled));
		}
		String preloadProfile = normalizedExtra("preload", "");
		if (!preloadProfile.isEmpty()) {
			applyPreloadProfile(repository, preloadProfile);
		}
		String graphicsPreset = normalizedExtra("graphics_preset", "");
		if (!graphicsPreset.isEmpty()) {
			repository.applyGraphicsPreset(graphicsPreset);
		}
		String displayPreset = normalizedExtra("display_preset", "");
		if (!displayPreset.isEmpty()) {
			repository.applyDisplayPreset(displayPreset);
		}
		String operationPreset = normalizedExtra("operation_preset", "");
		if (!operationPreset.isEmpty()) {
			repository.applyOperationPreset(operationPreset);
		}
		String aspectRatio = normalizedExtra("aspect_ratio", "");
		if (!aspectRatio.isEmpty()) {
			repository.applyAspectRatio(aspectRatio);
		}
		String settingsJson = extra(intent, "settings_json").trim();
		if (!settingsJson.isEmpty()) {
			mergeSettingsJson(repository, settingsJson);
		}
		if (hasExtra("install_bundled_compat")) {
			int installed = new CompatPackManager(context).installBundledCompatPacks();
			putEvent("install_bundled_compat", Integer.toString(installed));
		}
	}

	private void applyModeDefaults(ExtraSettingsRepository repository, String mode) throws Exception {
		String normalized = mode.trim().toLowerCase(Locale.ROOT);
		if ("compat".equals(normalized)) {
			repository.saveLogLevel(ExtraSettingsRepository.LOG_LEVEL_DEBUG);
			repository.saveSetting(settings -> settings.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, true));
		} else if ("mod".equals(normalized) || "mods".equals(normalized)) {
			repository.saveLogLevel(ExtraSettingsRepository.LOG_LEVEL_DEBUG);
			repository.saveSetting(settings -> repository.ensureModSettings(settings).put("mods_enabled", true));
		} else if ("perf".equals(normalized) || "performance".equals(normalized)) {
			repository.saveLogLevel(ExtraSettingsRepository.LOG_LEVEL_DEBUG);
			repository.savePerformanceOverlayEnabled(true);
		} else if ("preload".equals(normalized)) {
			repository.saveLogLevel(ExtraSettingsRepository.LOG_LEVEL_DEBUG);
			repository.savePerformanceOverlayEnabled(true);
		} else if ("launcher".equals(normalized)) {
			repository.saveLogLevel(ExtraSettingsRepository.LOG_LEVEL_INFO);
		}
		putEvent("mode", normalized);
	}

	private void applyPreloadProfile(ExtraSettingsRepository repository, String profile) throws Exception {
		String normalized = profile.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		repository.saveSetting(settings -> {
			if ("off".equals(normalized) || "disabled".equals(normalized)) {
				settings.put("preload_enabled", false);
				settings.put("preload_startup_common_enabled", false);
				settings.put("preload_startup_main_menu_enabled", false);
				settings.put("preload_menu_hotspots_enabled", false);
				settings.put("preload_vfx_mode", "off");
				settings.put("preload_vfx_tree_warmup_enabled", false);
				settings.put("preload_vfx_tree_warmup_scope", "safe");
				settings.put("preload_vfx_tree_warmup_frames", 3);
				settings.put("preload_vfx_retain_cache_enabled", false);
				settings.put("preload_combat_animation_warmup_mode", "off");
				settings.put("preload_combat_animation_warmup_frames", 1);
				settings.put("preload_combat_hit_effect_warmup_enabled", false);
				settings.put("preload_combat_code_enabled", false);
				settings.put("preload_shader_mode", "off");
				settings.put("preload_runtime_enabled", false);
				settings.put("preload_protect_warm_cache_enabled", false);
				settings.put("preload_gameplay_assets_enabled", false);
				settings.put("preload_learned_assets_enabled", false);
			} else if ("aggressive".equals(normalized) || "full".equals(normalized)) {
				putAggressivePreloadSettings(settings, true, "safe");
			} else if ("animation_full".equals(normalized) || "spine_full".equals(normalized) || "all_animations".equals(normalized)) {
				putAggressivePreloadSettings(settings, true, "all", "all");
			} else if ("vfx_full_tree".equals(normalized) || "full_tree".equals(normalized) || "all_vfx_tree".equals(normalized) || "all_resources".equals(normalized)) {
				putAggressivePreloadSettings(settings, true, "safe", "all");
			} else if ("tree_warmup".equals(normalized) || "render_warmup".equals(normalized) || "aggressive_tree".equals(normalized)) {
				putAggressivePreloadSettings(settings, true, "safe");
			} else if ("runtime_only".equals(normalized)) {
				settings.put("preload_enabled", true);
				settings.put("preload_startup_common_enabled", false);
				settings.put("preload_startup_main_menu_enabled", false);
				settings.put("preload_menu_hotspots_enabled", false);
				settings.put("preload_vfx_mode", "off");
				settings.put("preload_vfx_tree_warmup_enabled", false);
				settings.put("preload_vfx_tree_warmup_scope", "safe");
				settings.put("preload_vfx_tree_warmup_frames", 3);
				settings.put("preload_vfx_retain_cache_enabled", false);
				settings.put("preload_combat_animation_warmup_mode", "off");
				settings.put("preload_combat_animation_warmup_frames", 1);
				settings.put("preload_combat_hit_effect_warmup_enabled", false);
				settings.put("preload_combat_code_enabled", false);
				settings.put("preload_shader_mode", "off");
				settings.put("preload_runtime_enabled", true);
				settings.put("preload_protect_warm_cache_enabled", true);
				settings.put("preload_gameplay_assets_enabled", false);
				settings.put("preload_learned_assets_enabled", true);
			} else if ("startup_only".equals(normalized)) {
				settings.put("preload_enabled", true);
				settings.put("preload_startup_common_enabled", true);
				settings.put("preload_startup_main_menu_enabled", true);
				settings.put("preload_menu_hotspots_enabled", true);
				settings.put("preload_vfx_mode", "hot");
				settings.put("preload_vfx_tree_warmup_enabled", false);
				settings.put("preload_vfx_tree_warmup_scope", "safe");
				settings.put("preload_vfx_tree_warmup_frames", 3);
				settings.put("preload_vfx_retain_cache_enabled", false);
				settings.put("preload_combat_animation_warmup_mode", "off");
				settings.put("preload_combat_animation_warmup_frames", 1);
				settings.put("preload_combat_hit_effect_warmup_enabled", false);
				settings.put("preload_combat_code_enabled", false);
				settings.put("preload_shader_mode", "off");
				settings.put("preload_runtime_enabled", false);
				settings.put("preload_protect_warm_cache_enabled", true);
				settings.put("preload_gameplay_assets_enabled", false);
				settings.put("preload_learned_assets_enabled", true);
			} else {
				settings.put("preload_enabled", true);
				settings.put("preload_startup_common_enabled", true);
				settings.put("preload_startup_main_menu_enabled", true);
				settings.put("preload_menu_hotspots_enabled", false);
				settings.put("preload_vfx_mode", "off");
				settings.put("preload_vfx_tree_warmup_enabled", false);
				settings.put("preload_vfx_tree_warmup_scope", "safe");
				settings.put("preload_vfx_tree_warmup_frames", 3);
				settings.put("preload_vfx_retain_cache_enabled", false);
				settings.put("preload_combat_animation_warmup_mode", "off");
				settings.put("preload_combat_animation_warmup_frames", 1);
				settings.put("preload_combat_hit_effect_warmup_enabled", false);
				settings.put("preload_combat_code_enabled", false);
				settings.put("preload_shader_mode", "off");
				settings.put("preload_runtime_enabled", true);
				settings.put("preload_protect_warm_cache_enabled", true);
				settings.put("preload_gameplay_assets_enabled", false);
				settings.put("preload_learned_assets_enabled", true);
			}
		});
		putEvent("preload", normalized);
	}

	private static void putAggressivePreloadSettings(JSONObject settings, boolean treeWarmup, String combatAnimationMode) throws JSONException {
		putAggressivePreloadSettings(settings, treeWarmup, combatAnimationMode, "safe");
	}

	private static void putAggressivePreloadSettings(JSONObject settings, boolean treeWarmup, String combatAnimationMode, String vfxTreeWarmupScope) throws JSONException {
		settings.put("preload_enabled", true);
		settings.put("preload_startup_common_enabled", true);
		settings.put("preload_startup_main_menu_enabled", true);
		settings.put("preload_menu_hotspots_enabled", true);
		settings.put("preload_vfx_mode", "full");
		settings.put("preload_combat_code_enabled", true);
		settings.put("preload_shader_mode", "load_resources");
		settings.put("preload_runtime_enabled", true);
		settings.put("preload_debug_enabled", false);
		settings.put("preload_vfx_tree_warmup_enabled", treeWarmup);
		settings.put("preload_vfx_tree_warmup_scope", vfxTreeWarmupScope);
		settings.put("preload_vfx_tree_warmup_frames", 6);
		settings.put("preload_vfx_retain_cache_enabled", true);
		settings.put("preload_combat_animation_warmup_mode", combatAnimationMode);
		settings.put("preload_combat_animation_warmup_frames", "all".equals(combatAnimationMode) ? 2 : 1);
		settings.put("preload_combat_hit_effect_warmup_enabled", true);
		settings.put("preload_protect_warm_cache_enabled", true);
		settings.put("preload_gameplay_assets_enabled", true);
		settings.put("preload_learned_assets_enabled", true);
	}

	private void mergeSettingsJson(ExtraSettingsRepository repository, String settingsJson) throws Exception {
		JSONObject patch = new JSONObject(settingsJson);
		repository.saveSetting(settings -> {
			java.util.Iterator<String> keys = patch.keys();
			while (keys.hasNext()) {
				String key = keys.next();
				settings.put(key, patch.get(key));
			}
		});
		JSONObject merged = repository.loadSettingsJson();
		if (merged.has(ExtraSettingsRepository.KEY_LOG_LEVEL)) {
			ExtraSettingsPreferences.setLogLevel(context, ExtraSettingsRepository.normalizeLogLevel(merged.optString(ExtraSettingsRepository.KEY_LOG_LEVEL, ExtraSettingsRepository.LOG_LEVEL_INFO)));
		}
		if (merged.has(ExtraSettingsRepository.KEY_PERFORMANCE_OVERLAY_ENABLED)) {
			ExtraSettingsPreferences.setPerformanceOverlayEnabled(context, merged.optBoolean(ExtraSettingsRepository.KEY_PERFORMANCE_OVERLAY_ENABLED, false));
		}
		if (merged.has(ExtraSettingsRepository.KEY_HIGH_REFRESH_RATE_ENABLED)) {
			ExtraSettingsPreferences.setHighRefreshRateEnabled(context, merged.optBoolean(ExtraSettingsRepository.KEY_HIGH_REFRESH_RATE_ENABLED, true));
		}
		putEvent("settings_json", "merged");
	}

	private void importPayloadIfRequested(JSONObject details) throws Exception {
		String path = normalizedExtra("payload_path", "");
		if (path.isEmpty()) {
			return;
		}
		File file = resolveAutomationFile(path);
		putEvent("payload_import_begin", file.getAbsolutePath());
		long started = SystemClock.uptimeMillis();
		PayloadManager.Status status = new PayloadManager(context).importPayloadZip(Uri.fromFile(file), (percent, message) -> {
			if (percent == 1 || percent == 36 || percent == 86 || percent == 96 || percent == 100) {
				Log.i(TAG, "payload import " + percent + "% " + message);
			}
		}, null);
		JSONObject payload = new JSONObject();
		payload.put("ready", status.ready);
		payload.put("message", status.message);
		payload.put("version", status.version);
		payload.put("commit", status.commit);
		payload.put("game_dir", status.gameDir == null ? "" : status.gameDir.getAbsolutePath());
		payload.put("elapsed_ms", SystemClock.uptimeMillis() - started);
		details.put("payload_import", payload);
		putEvent("payload_import_end", status.shortVersionLabel());
	}

	private void selectAndUpdateProfile(JSONObject details) throws Exception {
		LaunchProfileManager manager = new LaunchProfileManager(context);
		manager.bootstrapIfNeeded();
		String profileId = normalizedExtra("profile_id", "");
		if (!profileId.isEmpty()) {
			manager.selectProfile(profileId);
			putEvent("profile_selected", profileId);
		} else {
			String payloadId = normalizedExtra("payload_id", "");
			if (!payloadId.isEmpty()) {
				LaunchProfileManager.LaunchProfile profile = findProfileForPayload(manager, payloadId);
				if (profile == null && booleanExtra("create_profile", true)) {
					LaunchProfileManager.GamePayload payload = manager.readPayload(payloadId);
					if (payload != null && payload.ready) {
						profile = manager.createProfile(payload.id, normalizedExtra("profile_name", payload.label), valueOrDefault(normalizedExtra("save_mode", ""), LaunchProfileManager.SAVE_MODE_GLOBAL), valueOrDefault(normalizedExtra("mods_mode", ""), LaunchProfileManager.MODS_MODE_GLOBAL), true);
					}
				}
				if (profile != null) {
					manager.selectProfile(profile.id);
					putEvent("profile_selected_for_payload", profile.id);
				}
			}
		}
		LaunchProfileManager.LaunchProfile selected = manager.getSelectedProfile();
		if (selected != null && (hasExtra("save_mode") || hasExtra("mods_mode") || hasExtra("profile_name"))) {
			String displayName = valueOrDefault(normalizedExtra("profile_name", ""), selected.displayName);
			String saveMode = valueOrDefault(normalizedExtra("save_mode", ""), selected.saveMode);
			String modsMode = valueOrDefault(normalizedExtra("mods_mode", ""), selected.modsMode);
			manager.updateProfile(selected.id, selected.payloadId, displayName, saveMode, modsMode, selected.compatPackId, selected.compatTargetId);
			putEvent("profile_updated", selected.id);
		}
		LaunchProfileManager.LaunchProfile updated = manager.getSelectedProfile();
		if (updated != null) {
			JSONObject profile = new JSONObject();
			profile.put("id", updated.id);
			profile.put("payload_id", updated.payloadId);
			profile.put("save_mode", updated.saveMode);
			profile.put("mods_mode", updated.modsMode);
			details.put("selected_profile", profile);
		}
	}

	private LaunchProfileManager.LaunchProfile findProfileForPayload(LaunchProfileManager manager, String payloadId) {
		for (LaunchProfileManager.LaunchProfile profile : manager.listProfiles()) {
			if (payloadId.equals(profile.payloadId)) {
				return profile;
			}
		}
		return null;
	}

	private void importCompatIfRequested(JSONObject details) throws Exception {
		String path = normalizedExtra("compat_path", "");
		if (path.isEmpty()) {
			return;
		}
		File file = resolveAutomationFile(path);
		CompatPackManager manager = new CompatPackManager(context);
		putEvent("compat_import_begin", file.getAbsolutePath());
		CompatPackManager.CompatPack installed = manager.importCompatPack(Uri.fromFile(file));
		JSONObject compat = compatToJson(installed);
		details.put("compat_import", compat);
		if (booleanExtra("select_imported_compat", true)) {
			CompatPackManager.CompatPack selection = chooseCompatAfterImport(manager, installed);
			if (selection != null) {
				String explicitTarget = normalizedExtra("compat_target_id", "");
				manager.selectPack(selection.packId, explicitTarget.isEmpty() ? selection.targetId : explicitTarget);
				putEvent("compat_selected", selection.selectionLabel());
			}
		}
	}

	private CompatPackManager.CompatPack chooseCompatAfterImport(CompatPackManager manager, CompatPackManager.CompatPack installed) {
		String explicitPackId = normalizedExtra("compat_pack_id", "");
		String explicitTargetId = normalizedExtra("compat_target_id", "");
		if (!explicitPackId.isEmpty()) {
			for (CompatPackManager.CompatPack pack : manager.listInstalledPacks()) {
				if (explicitPackId.equals(pack.packId) && (explicitTargetId.isEmpty() || explicitTargetId.equals(pack.targetId))) {
					return pack;
				}
			}
		}
		LaunchProfileManager.GamePayload payload = new LaunchProfileManager(context).getSelectedPayload();
		if (payload != null) {
			CompatPackManager.CompatPack best = manager.findBestMatch(payload.manifest);
			if (best != null && (installed == null || best.packId.equals(installed.packId))) {
				return best;
			}
		}
		return installed;
	}

	private void selectCompatIfRequested(JSONObject details) throws Exception {
		String packId = normalizedExtra("compat_pack_id", "");
		String targetId = normalizedExtra("compat_target_id", "");
		if (packId.isEmpty()) {
			return;
		}
		new CompatPackManager(context).selectPack(packId, targetId);
		JSONObject selected = compatToJson(new CompatPackManager(context).getSelectedPackIgnoringEnabled());
		details.put("selected_compat", selected);
		putEvent("compat_selected_explicit", targetId.isEmpty() ? packId : packId + "/" + targetId);
	}

	private void importModsIfRequested(JSONObject details) throws Exception {
		List<String> paths = splitList(normalizedExtra("mod_paths", ""));
		if (paths.isEmpty()) {
			return;
		}
		ExtraSettingsRepository repository = new ExtraSettingsRepository(context);
		Set<String> beforeIds = new LinkedHashSet<>();
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			beforeIds.add(entry.modId);
		}
		JSONArray imported = new JSONArray();
		for (String path : paths) {
			File file = resolveAutomationFile(path);
			putEvent("mod_import_begin", file.getAbsolutePath());
			String displayName = file.getName();
			String normalizedName = repository.importDownloadedModFile(file, displayName);
			JSONObject item = new JSONObject();
			item.put("source", file.getAbsolutePath());
			item.put("normalized_name", normalizedName);
			imported.put(item);
		}
		repository.saveSetting(settings -> repository.ensureModSettings(settings).put("mods_enabled", true));
		Set<String> importedIds = new LinkedHashSet<>();
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			if (!beforeIds.contains(entry.modId)) {
				importedIds.add(entry.modId);
			}
		}
		JSONArray importedIdArray = new JSONArray();
		for (String modId : importedIds) {
			importedIdArray.put(modId);
		}
		details.put("mod_imports", imported);
		details.put("new_mod_ids", importedIdArray);
		putEvent("mod_import_end", Integer.toString(paths.size()));
		if (booleanExtra("mods_only_imported", false) && !importedIds.isEmpty() && normalizedExtra("mods_only", "").isEmpty()) {
			applyModsOnly(repository, importedIds);
			putEvent("mods_only_imported", importedIds.toString());
		}
	}

	private void applyModSelectionIfRequested(JSONObject details) throws Exception {
		if (!hasExtra("mods_enabled") && !hasExtra("mods_only") && !hasExtra("mods_enable") && !hasExtra("mods_disable")) {
			return;
		}
		ExtraSettingsRepository repository = new ExtraSettingsRepository(context);
		if (hasExtra("mods_enabled")) {
			boolean enabled = booleanExtra("mods_enabled", true);
			repository.saveSetting(settings -> repository.ensureModSettings(settings).put("mods_enabled", enabled));
			putEvent("mods_enabled", String.valueOf(enabled));
		}
		Set<String> only = new LinkedHashSet<>(splitList(normalizedExtra("mods_only", "")));
		if (!only.isEmpty()) {
			applyModsOnly(repository, only);
			putEvent("mods_only", only.toString());
		}
		Set<String> enable = new LinkedHashSet<>(splitList(normalizedExtra("mods_enable", "")));
		Set<String> disable = new LinkedHashSet<>(splitList(normalizedExtra("mods_disable", "")));
		if (!enable.isEmpty() || !disable.isEmpty()) {
			JSONObject settings = repository.loadSettingsJson();
			for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
				if (matchesMod(enable, entry)) {
					repository.setModDisabled(settings, entry.modId, false);
					if (!entry.pckName.equals(entry.modId)) {
						repository.setModDisabled(settings, entry.pckName, false);
					}
				}
				if (matchesMod(disable, entry)) {
					repository.setModDisabled(settings, entry.modId, true);
					if (!entry.pckName.equals(entry.modId)) {
						repository.setModDisabled(settings, entry.pckName, true);
					}
				}
			}
			repository.saveSettingsJson(settings);
		}
		details.put("mods", modsToJson(repository));
	}

	private void applyModsOnly(ExtraSettingsRepository repository, Set<String> enabledIds) throws Exception {
		JSONObject settings = repository.loadSettingsJson();
		repository.ensureModSettings(settings).put("mods_enabled", true);
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			boolean enabled = matchesMod(enabledIds, entry);
			repository.setModDisabled(settings, entry.modId, !enabled);
			if (!entry.pckName.equals(entry.modId)) {
				repository.setModDisabled(settings, entry.pckName, !enabled);
			}
		}
		repository.saveSettingsJson(settings);
	}

	private boolean matchesMod(Set<String> candidates, ExtraSettingsRepository.ModEntry entry) {
		if (candidates == null || candidates.isEmpty() || entry == null) {
			return false;
		}
		return candidates.contains(entry.modId) || candidates.contains(entry.pckName) || candidates.contains(entry.displayName);
	}

	private void clearRequestedState(JSONObject details) throws Exception {
		JSONArray cleared = new JSONArray();
		Set<String> clearItems = new LinkedHashSet<>(splitList(normalizedExtra("clear", "")));
		addBooleanClear(clearItems, "texture", "clear_texture_cache");
		addBooleanClear(clearItems, "publish", "clear_publish");
		addBooleanClear(clearItems, "logs", "clear_logs");
		addBooleanClear(clearItems, "mods", "clear_mods");
		addBooleanClear(clearItems, "compat", "clear_compat");
		addBooleanClear(clearItems, "payloads", "clear_payloads");
		addBooleanClear(clearItems, "automation", "clear_automation");
		if (clearItems.contains("texture") || clearItems.contains("texture_cache")) {
			new GameLaunchPreparationManager(context).clearTextureCacheForNextLaunch();
			cleared.put("texture_cache");
		}
		if (clearItems.contains("publish") || clearItems.contains("assemblies")) {
			FileBrowserSupport.deleteRecursively(new File(context.getFilesDir(), ".godot/mono/publish/arm64"));
			cleared.put("publish");
		}
		if (clearItems.contains("logs")) {
			clearLogs();
			cleared.put("logs");
		}
		if (clearItems.contains("mods")) {
			ExtraSettingsRepository repository = new ExtraSettingsRepository(context);
			File modsRoot = repository.getModsRootDir();
			FileBrowserSupport.deleteRecursively(modsRoot);
			FileBrowserSupport.ensureDirectory(modsRoot);
			cleared.put("mods");
		}
		if (clearItems.contains("compat")) {
			FileBrowserSupport.deleteRecursively(new CompatPackManager(context).getCompatPacksRootDir());
			FileBrowserSupport.ensureDirectory(new CompatPackManager(context).getCompatPacksRootDir());
			cleared.put("compat");
		}
		if (clearItems.contains("payloads")) {
			LaunchProfileManager manager = new LaunchProfileManager(context);
			FileBrowserSupport.deleteRecursively(manager.getPayloadsRootDir());
			FileBrowserSupport.deleteRecursively(manager.getProfilesRootDir());
			manager.bootstrapIfNeeded();
			cleared.put("payloads");
		}
		if (clearItems.contains("automation")) {
			clearOldAutomationRuns();
			cleared.put("automation");
		}
		if (cleared.length() > 0) {
			details.put("cleared", cleared);
			putEvent("cleared", cleared.toString());
		}
	}

	private void addBooleanClear(Set<String> clearItems, String item, String extraName) {
		if (booleanExtra(extraName, false)) {
			clearItems.add(item);
		}
	}

	private void clearLogs() {
		FileBrowserSupport.deleteRecursively(new File(context.getFilesDir(), "logs"));
		File profilesRoot = new LaunchProfileManager(context).getProfilesRootDir();
		File[] profiles = profilesRoot.listFiles(File::isDirectory);
		if (profiles != null) {
			for (File profile : profiles) {
				FileBrowserSupport.deleteRecursively(new File(profile, "logs"));
			}
		}
	}

	private void clearOldAutomationRuns() {
		File root = new File(context.getFilesDir(), "automation/runs");
		File[] runs = root.listFiles(File::isDirectory);
		if (runs == null) {
			return;
		}
		for (File run : runs) {
			if (!run.getName().equals(runId)) {
				FileBrowserSupport.deleteRecursively(run);
			}
		}
	}

	private void prepareLaunch(JSONObject details) throws Exception {
		long started = SystemClock.uptimeMillis();
		new GameLaunchPreparationManager(context).prepareForLaunch();
		long elapsed = SystemClock.uptimeMillis() - started;
		details.put("prepare_elapsed_ms", elapsed);
		putEvent("prepare", elapsed + "ms");
	}

	private void startGameActivity(JSONObject details, boolean launchPrepared) throws Exception {
		Intent gameIntent = new Intent(context, GodotApp.class);
		gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		gameIntent.putExtra("launch_prepared", launchPrepared);
		context.startActivity(gameIntent);
		details.put("started_activity", "GodotApp");
		details.put("launch_prepared", launchPrepared);
		putEvent("start_activity", "GodotApp");
	}

	private void startSettingsActivity(JSONObject details) throws Exception {
		Intent settingsIntent = new Intent(context, GameSettingsActivity.class);
		settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		context.startActivity(settingsIntent);
		details.put("started_activity", "GameSettingsActivity");
		putEvent("start_activity", "GameSettingsActivity");
	}

	private JSONObject buildStatus() throws Exception {
		LaunchProfileManager launchProfiles = new LaunchProfileManager(context);
		launchProfiles.bootstrapIfNeeded();
		ExtraSettingsRepository repository = new ExtraSettingsRepository(context);
		JSONObject status = new JSONObject();
		status.put("package", context.getPackageName());
		status.put("version_name", BuildConfig.VERSION_NAME);
		status.put("version_code", BuildConfig.VERSION_CODE);
		status.put("files_dir", context.getFilesDir().getAbsolutePath());
		status.put("first_run_completed", ExtraSettingsPreferences.isFirstRunSetupCompleted(context));
		status.put("renderer", RendererPreference.getSelectedRenderer(context));
		status.put("selected_profile_id", launchProfiles.getSelectedProfileId());
		LaunchProfileManager.LaunchProfile selectedProfile = launchProfiles.getSelectedProfile();
		if (selectedProfile != null) {
			status.put("selected_profile", profileToJson(selectedProfile));
		}
		PayloadManager.Status payload = new PayloadManager(context).getStatus();
		JSONObject payloadJson = new JSONObject();
		payloadJson.put("ready", payload.ready);
		payloadJson.put("message", payload.message);
		payloadJson.put("version", payload.version);
		payloadJson.put("commit", payload.commit);
		payloadJson.put("game_dir", payload.gameDir == null ? "" : payload.gameDir.getAbsolutePath());
		status.put("selected_payload", payloadJson);
		JSONObject settings = repository.loadSettingsJson();
		JSONObject importantSettings = new JSONObject();
		for (String key : new String[] {
			ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED,
			ExtraSettingsRepository.KEY_LOG_LEVEL,
			ExtraSettingsRepository.KEY_PERFORMANCE_OVERLAY_ENABLED,
			ExtraSettingsRepository.KEY_HIGH_REFRESH_RATE_ENABLED,
			"preload_enabled",
			"preload_startup_common_enabled",
			"preload_startup_main_menu_enabled",
			"preload_menu_hotspots_enabled",
			"preload_vfx_mode",
			"preload_combat_code_enabled",
			"preload_shader_mode",
			"preload_runtime_enabled",
			"preload_debug_enabled",
			"preload_vfx_tree_warmup_enabled",
			"preload_vfx_tree_warmup_scope",
			"preload_vfx_tree_warmup_frames",
			"preload_vfx_retain_cache_enabled",
			"preload_combat_animation_warmup_mode",
			"preload_combat_animation_warmup_frames",
			"preload_combat_hit_effect_warmup_enabled",
			"preload_protect_warm_cache_enabled",
			"preload_gameplay_assets_enabled",
			"preload_learned_assets_enabled",
			"mod_settings"
		}) {
			if (settings.has(key)) {
				importantSettings.put(key, settings.get(key));
			}
		}
		status.put("settings", importantSettings);
		JSONArray profiles = new JSONArray();
		for (LaunchProfileManager.LaunchProfile profile : launchProfiles.listProfiles()) {
			profiles.put(profileToJson(profile));
		}
		status.put("profiles", profiles);
		JSONArray packs = new JSONArray();
		for (CompatPackManager.CompatPack pack : new CompatPackManager(context).listInstalledPacks()) {
			packs.put(compatToJson(pack));
		}
		status.put("compat_packs", packs);
		status.put("selected_compat", compatToJson(new CompatPackManager(context).getSelectedPackIgnoringEnabled()));
		status.put("mods", modsToJson(repository));
		status.put("paths", pathsToJson(launchProfiles, repository));
		return status;
	}

	private JSONObject profileToJson(LaunchProfileManager.LaunchProfile profile) throws Exception {
		JSONObject json = new JSONObject();
		json.put("id", profile.id);
		json.put("display_name", profile.displayName);
		json.put("payload_id", profile.payloadId);
		json.put("compat_pack_id", profile.compatPackId);
		json.put("compat_target_id", profile.compatTargetId);
		json.put("save_mode", profile.saveMode);
		json.put("mods_mode", profile.modsMode);
		json.put("ready", profile.ready);
		json.put("dir", profile.dir.getAbsolutePath());
		return json;
	}

	private JSONObject compatToJson(CompatPackManager.CompatPack pack) throws Exception {
		JSONObject json = new JSONObject();
		if (pack == null) {
			json.put("ready", false);
			return json;
		}
		json.put("pack_id", pack.packId);
		json.put("target_id", pack.targetId);
		json.put("display_name", pack.displayName);
		json.put("pack_kind", pack.packKind);
		json.put("match_mode", pack.matchMode);
		json.put("selection_priority", pack.selectionPriority);
		json.put("compat_version", pack.compatVersion);
		json.put("channel", pack.channel);
		json.put("target_version", pack.targetVersion);
		json.put("target_label", pack.targetLabel());
		json.put("target_sts2_dll_sha256", pack.targetSts2DllSha256);
		json.put("ready", pack.ready);
		json.put("dir", pack.dir.getAbsolutePath());
		json.put("dll", pack.dllFile.getAbsolutePath());
		json.put("overlay", pack.overlayPckFile.getAbsolutePath());
		return json;
	}

	private JSONArray modsToJson(ExtraSettingsRepository repository) throws Exception {
		JSONObject settings = repository.loadSettingsJson();
		JSONArray mods = new JSONArray();
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			JSONObject mod = new JSONObject();
			mod.put("id", entry.modId);
			mod.put("pck_name", entry.pckName);
			mod.put("display_name", entry.displayName);
			mod.put("version", entry.version);
			mod.put("enabled", !repository.isModDisabled(settings, entry));
			mod.put("relative_path", entry.relativePath);
			mod.put("manifest", entry.manifestFile.getAbsolutePath());
			mods.put(mod);
		}
		return mods;
	}

	private JSONObject pathsToJson(LaunchProfileManager launchProfiles, ExtraSettingsRepository repository) throws Exception {
		JSONObject paths = new JSONObject();
		paths.put("selected_game_dir", launchProfiles.getSelectedGameDir().getAbsolutePath());
		paths.put("selected_account_root", launchProfiles.getSelectedAccountRootDir().getAbsolutePath());
		paths.put("selected_mods_root", launchProfiles.getSelectedModsRootDir().getAbsolutePath());
		paths.put("selected_logs_root", launchProfiles.getSelectedLogsRootDir().getAbsolutePath());
		paths.put("settings_file", repository.getSettingsFile().getAbsolutePath());
		paths.put("automation_run_dir", runDir.getAbsolutePath());
		return paths;
	}

	private File resolveAutomationFile(String path) throws IOException {
		if (TextUtils.isEmpty(path)) {
			throw new IOException("Missing automation file path.");
		}
		File file = new File(path);
		if (!file.isAbsolute()) {
			file = new File(context.getDataDir(), path);
		}
		File dataRoot = context.getDataDir().getCanonicalFile();
		File canonical = file.getCanonicalFile();
		if (!FileBrowserSupport.isSameOrDescendant(canonical, dataRoot)) {
			throw new IOException("Automation file must be inside app data: " + path);
		}
		if (!canonical.isFile()) {
			throw new IOException("Automation file not found: " + canonical.getAbsolutePath());
		}
		return canonical;
	}

	private List<String> splitList(String raw) {
		List<String> values = new ArrayList<>();
		if (raw == null || raw.trim().isEmpty()) {
			return values;
		}
		String[] parts = raw.contains("|") ? raw.split("\\|") : raw.split(",");
		for (String part : parts) {
			String value = part == null ? "" : part.trim();
			if (!value.isEmpty() && !values.contains(value)) {
				values.add(value);
			}
		}
		return values;
	}

	private boolean hasExtra(String key) {
		Bundle extras = intent.getExtras();
		return extras != null && extras.containsKey(key);
	}

	private boolean booleanExtra(String key, boolean fallback) {
		if (!hasExtra(key)) {
			return fallback;
		}
		String value = extra(intent, key).trim().toLowerCase(Locale.ROOT);
		if (value.isEmpty()) {
			return fallback;
		}
		if ("1".equals(value) || "true".equals(value) || "yes".equals(value) || "y".equals(value) || "on".equals(value)) {
			return true;
		}
		if ("0".equals(value) || "false".equals(value) || "no".equals(value) || "n".equals(value) || "off".equals(value)) {
			return false;
		}
		return fallback;
	}

	private int intExtra(String key, int fallback) {
		String value = normalizedExtra(key, "");
		if (TextUtils.isEmpty(value)) {
			return fallback;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private String normalizedExtra(String key, String fallback) {
		String value = extra(intent, key);
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		return value.trim();
	}

	private String valueOrDefault(String value, String fallback) {
		return TextUtils.isEmpty(value) ? fallback : value;
	}

	private JSONObject sanitizedExtras() throws Exception {
		return sanitizedExtras(intent);
	}

	private static JSONObject sanitizedExtras(Intent intent) throws Exception {
		JSONObject json = new JSONObject();
		Bundle extras = intent == null ? null : intent.getExtras();
		if (extras == null) {
			return json;
		}
		for (String key : extras.keySet()) {
			if ("token".equals(key)) {
				json.put(key, "<redacted>");
			} else {
				Object value = extras.get(key);
				json.put(key, value == null ? JSONObject.NULL : String.valueOf(value));
			}
		}
		return json;
	}

	private void putEvent(String name, String message) {
		try {
			JSONObject event = new JSONObject();
			event.put("t_ms", System.currentTimeMillis() - startedAtMillis);
			event.put("name", name);
			event.put("message", message == null ? "" : message);
			events.put(event);
			Log.i(TAG, runId + " " + name + " " + (message == null ? "" : message));
		} catch (Exception ignored) {
		}
	}

	private void writeRequestSnapshot(String status) throws Exception {
		JSONObject request = new JSONObject();
		request.put("schema", 1);
		request.put("run_id", runId);
		request.put("status", status);
		request.put("created_at_unix", System.currentTimeMillis() / 1000L);
		request.put("extras", sanitizedExtras());
		FileBrowserSupport.writeTextFile(new File(runDir, "request.json"), request.toString(2));
	}

	private void writeResult() throws Exception {
		String text = result.toString(2);
		FileBrowserSupport.writeTextFile(new File(runDir, "result.json"), text);
		FileBrowserSupport.writeTextFile(new File(context.getFilesDir(), LAST_RESULT_RELATIVE_PATH), text);
	}

	private String messageFor(Throwable throwable) {
		if (throwable == null) {
			return "unknown";
		}
		String message = throwable.getMessage();
		if (TextUtils.isEmpty(message)) {
			return throwable.toString();
		}
		return throwable.getClass().getSimpleName() + ": " + message;
	}
}
