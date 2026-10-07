package com.godot.game;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotRenderView;

final class HighRefreshRateController {
	private static final String TAG = "Sts2Re";
	private static final float MIN_TARGET_HZ = 61.0f;
	private static final float TARGET_60_HZ = 60.0f;
	private static final float REFRESH_RATE_TOLERANCE_HZ = 0.5f;
	private static final long[] RETRY_DELAYS_MS = {100L, 500L, 1500L};
	private static final long VERIFY_DELAY_MS = 1200L;

	enum RefreshRateMode {
		HIGH,
		HZ60,
		SYSTEM
	}

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private Activity activity;
	private Godot godot;
	private RefreshRateMode requestedMode = RefreshRateMode.SYSTEM;
	private boolean enabled;
	private boolean resumed;
	private boolean focused;
	private boolean destroyed;
	private int generation;
	private int waitingLoggedGeneration = -1;
	private Runnable pendingRetry;
	private Runnable pendingSurfaceRequest;
	private Runnable pendingVerification;
	private SurfaceView targetSurfaceView;
	private SurfaceHolder targetSurfaceHolder;
	private int surfaceEpoch;
	private int appliedGeneration = -1;
	private Surface lastAppliedSurface;
	private int lastAppliedSurfaceEpoch = -1;
	private RefreshRateMode lastAppliedRequestMode;
	private int lastAppliedModeId;
	private float lastAppliedRefreshRate;
	private int lastAppliedBufferWidth;
	private int lastAppliedBufferHeight;

	private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			if (holder != targetSurfaceHolder) {
				return;
			}
			surfaceEpoch++;
			clearLastAppliedSurface();
			requestFromSurfaceCallback("surfaceCreated");
		}

		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			if (holder != targetSurfaceHolder) {
				return;
			}
			// An exact display-mode switch commonly produces surfaceChanged without
			// replacing the Surface. The existing frame-rate vote remains attached to
			// that Surface, so starting another generation here would only re-issue the
			// same request and could create a mode-switch feedback loop.
			if (wasAppliedToCurrentHolderSurface()) {
				Log.d(TAG, diagnostic("surface_stable", "surfaceChanged",
					"size=" + width + "x" + height + "; requestAlreadyApplied=true"));
				return;
			}
			requestFromSurfaceCallback("surfaceChanged:" + width + "x" + height);
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			if (holder != targetSurfaceHolder) {
				return;
			}
			surfaceEpoch++;
			clearLastAppliedSurface();
			invalidatePendingWork();
			Log.i(TAG, diagnostic("cancelled", "surfaceDestroyed", "surfaceValid=false"));
		}
	};

	HighRefreshRateController() {
	}

	void request(Activity activity, Godot godot, RefreshRateMode mode, String reason) {
		RefreshRateMode requestedMode = mode == null ? RefreshRateMode.SYSTEM : mode;
		runOnUiThread(activity, () -> {
			if (destroyed) {
				return;
			}
			this.activity = activity;
			this.godot = godot;
			this.requestedMode = requestedMode;
			if (requestedMode == RefreshRateMode.SYSTEM) {
				boolean wasEnabled = enabled;
				enabled = false;
				invalidatePendingWork();
				String resetPath = resetPlatformRequest(activity);
				detachSurfaceCallback();
				clearLastAppliedSurface();
				Log.i(TAG, diagnostic("system", reason, "reset=" + resetPath + "; wasEnabled=" + wasEnabled));
				return;
			}
			enabled = true;
			startGeneration(reason);
		});
	}

	void onResumed(Activity activity, Godot godot, boolean hasFocus) {
		runOnUiThread(activity, () -> {
			if (destroyed) {
				return;
			}
			this.activity = activity;
			this.godot = godot;
			resumed = true;
			focused = hasFocus;
			clearLastAppliedSurface();
		});
	}

	void onWindowFocusChanged(Activity activity, Godot godot, boolean hasFocus) {
		runOnUiThread(activity, () -> {
			if (destroyed) {
				return;
			}
			this.activity = activity;
			this.godot = godot;
			focused = hasFocus;
			if (!hasFocus) {
				invalidatePendingWork();
				detachSurfaceCallback();
			}
		});
	}

	void onPaused(Activity activity) {
		runOnUiThread(activity, () -> {
			boolean shouldLog = enabled || hasPendingWork();
			resumed = false;
			focused = false;
			invalidatePendingWork();
			detachSurfaceCallback();
			clearLastAppliedSurface();
			if (shouldLog) {
				Log.i(TAG, diagnostic("cancelled", "onPause", ""));
			}
		});
	}

	void onDestroyed(Activity activity) {
		runOnUiThread(activity, () -> {
			boolean shouldLog = enabled || hasPendingWork();
			destroyed = true;
			resumed = false;
			focused = false;
			enabled = false;
			invalidatePendingWork();
			detachSurfaceCallback();
			this.godot = null;
			this.activity = null;
			if (shouldLog) {
				Log.i(TAG, diagnostic("cancelled", "onDestroy", ""));
			}
		});
	}

	private void startGeneration(String reason) {
		invalidatePendingWork();
		if (!canAttempt()) {
			Log.d(TAG, diagnostic("skipped", reason, "state=" + lifecycleWaitState()));
			return;
		}
		int expectedGeneration = generation;
		attemptApply(expectedGeneration, reason, 0);
	}

	private void requestFromSurfaceCallback(String reason) {
		if (!canAttempt()) {
			return;
		}
		if (pendingSurfaceRequest != null) {
			mainHandler.removeCallbacks(pendingSurfaceRequest);
		}
		pendingSurfaceRequest = () -> {
			pendingSurfaceRequest = null;
			if (!canAttempt()) {
				return;
			}
			if (wasAppliedToCurrentHolderSurface()) {
				Log.d(TAG, diagnostic("surface_stable", reason, "requestAlreadyApplied=true"));
				return;
			}
			startGeneration(reason);
		};
		mainHandler.post(pendingSurfaceRequest);
	}

	private void attemptApply(int expectedGeneration, String reason, int attempt) {
		if (expectedGeneration != generation || !canAttempt()) {
			return;
		}
		if (appliedGeneration == expectedGeneration) {
			cancelPendingRetry();
			return;
		}
		try {
			Window window = activity.getWindow();
			if (window == null) {
				scheduleRetry(expectedGeneration, reason, attempt, "window_missing");
				return;
			}

			ModeChoice choice = chooseMode(activity, requestedMode);
			if (!choice.supported) {
				// A mode can become unavailable after a previous request (for example when
				// an external display mode policy changes). Clear every old vote instead of
				// leaving the previous HIGH/HZ60 request active.
				appliedGeneration = expectedGeneration;
				cancelPendingRetry();
				String resetPath = resetPlatformRequest(activity);
				clearLastAppliedSurface();
				Log.i(TAG, diagnostic("unsupported", reason,
					"requested=" + requestedMode
						+ "; displayHz=" + choice.currentRefreshRate
						+ "; fallback=system"
						+ "; reset=" + resetPath));
				return;
			}

			View renderRoot = getRenderView(godot);
			if (renderRoot == null) {
				renderRoot = window.getDecorView();
			}
			SurfaceView surfaceView = findFirstSurfaceView(renderRoot);
			if (surfaceView == null) {
				scheduleRetry(expectedGeneration, reason, attempt, "surface_view_missing");
				return;
			}
			attachSurfaceCallback(surfaceView);
			if (!surfaceView.isAttachedToWindow()) {
				scheduleRetry(expectedGeneration, reason, attempt, "surface_view_detached");
				return;
			}

			SurfaceHolder holder = surfaceView.getHolder();
			Surface surface = holder == null ? null : holder.getSurface();
			if (surface == null || !surface.isValid()) {
				scheduleRetry(expectedGeneration, reason, attempt, "surface_invalid");
				return;
			}

			// Mark the generation before issuing either platform request. Both calls can
			// synchronously provoke window/surface callbacks on vendor Android builds;
			// those callbacks must never cause a second request in this generation.
			appliedGeneration = expectedGeneration;
			boolean reusedSurfaceVote = wasAppliedToCurrentSurface(surface, choice);
			String surfacePath = reusedSurfaceVote
				? "surface-existing-vote"
				: applySurfaceFrameRate(surface, choice.refreshRate);
			WindowRequest windowRequest = applyWindowMode(window, choice);
			if (expectedGeneration != generation || !surface.isValid()) {
				cancelPendingRetry();
				Log.i(TAG, diagnostic("surface_transition", reason,
					"requested=" + requestedMode
						+ "; targetMode=" + choice.modeId
						+ "; targetHz=" + choice.refreshRate
						+ "; window=" + windowRequest.path
						+ "; surface=" + surfacePath));
				return;
			}
			lastAppliedSurface = surface;
			lastAppliedSurfaceEpoch = surfaceEpoch;
			lastAppliedRequestMode = choice.requestMode;
			lastAppliedModeId = choice.modeId;
			lastAppliedRefreshRate = choice.refreshRate;
			recordAppliedBufferSize(holder);
			cancelPendingRetry();
			scheduleVerification(expectedGeneration, reason, choice);
			Log.i(TAG, diagnostic("applied", reason,
				"attempt=" + attempt
					+ "; requested=" + requestedMode
					+ "; mode=" + choice.modeId
					+ "; hz=" + choice.refreshRate
					+ "; selection=" + choice.selectionPath
					+ "; size=" + choice.width + "x" + choice.height
					+ "; beforeMode=" + choice.currentModeId
					+ "; beforeModeHz=" + choice.currentModeRefreshRate
					+ "; beforeDisplayHz=" + choice.currentRefreshRate
					+ "; window=" + windowRequest.path
					+ "; windowChanged=" + windowRequest.changed
					+ "; surface=" + surfacePath
					+ "; buffer=" + lastAppliedBufferWidth + "x" + lastAppliedBufferHeight
					+ "; view=" + surfaceView.getClass().getName()));
		} catch (Throwable throwable) {
			Log.w(TAG, diagnostic("apply_failed", reason, "attempt=" + attempt), throwable);
			if (appliedGeneration != expectedGeneration) {
				scheduleRetry(expectedGeneration, reason, attempt, "exception");
			}
		}
	}

	private void scheduleRetry(int expectedGeneration, String reason, int attempt, String waitState) {
		if (expectedGeneration != generation || !canAttempt()) {
			return;
		}
		if (attempt >= RETRY_DELAYS_MS.length) {
			cancelPendingRetry();
			Log.w(TAG, diagnostic("waiting_for_surface", reason,
				"state=" + waitState + "; retries_exhausted=true"));
			return;
		}
		if (waitingLoggedGeneration != expectedGeneration) {
			waitingLoggedGeneration = expectedGeneration;
			Log.i(TAG, diagnostic("waiting_for_surface", reason,
				"state=" + waitState + "; retryCount=" + RETRY_DELAYS_MS.length));
		}
		cancelPendingRetry();
		long delayMs = RETRY_DELAYS_MS[attempt];
		pendingRetry = () -> {
			pendingRetry = null;
			attemptApply(expectedGeneration, reason, attempt + 1);
		};
		mainHandler.postDelayed(pendingRetry, delayMs);
	}

	private void scheduleVerification(int expectedGeneration, String reason, ModeChoice choice) {
		cancelPendingVerification();
		pendingVerification = () -> {
			pendingVerification = null;
			if (expectedGeneration != generation || !canAttempt()) {
				return;
			}
			DisplayState observed = readDisplayState(activity);
			Window window = activity.getWindow();
			WindowManager.LayoutParams params = window == null ? null : window.getAttributes();
			int preferredModeId = params == null || Build.VERSION.SDK_INT < 23
				? 0
				: params.preferredDisplayModeId;
			float preferredRefreshRate = params == null ? 0.0f : params.preferredRefreshRate;
			boolean modeMatched = isModeMatched(choice.modeId, observed.modeId);
			boolean refreshMatched = isRefreshRateMatched(
				choice.requestMode,
				choice.refreshRate,
				observed.modeRefreshRate,
				observed.displayRefreshRate);
			boolean verified = modeMatched && refreshMatched;
			Log.i(TAG, diagnostic(verified ? "verified" : "verification_mismatch", reason,
				"requested=" + choice.requestMode
					+ "; targetMode=" + choice.modeId
					+ "; targetHz=" + choice.refreshRate
					+ "; selection=" + choice.selectionPath
					+ "; observedMode=" + observed.modeId
					+ "; observedModeHz=" + observed.modeRefreshRate
					+ "; observedDisplayHz=" + observed.displayRefreshRate
					+ "; preferredMode=" + preferredModeId
					+ "; preferredHz=" + preferredRefreshRate
					+ "; modeMatched=" + modeMatched
					+ "; refreshMatched=" + refreshMatched));
		};
		mainHandler.postDelayed(pendingVerification, VERIFY_DELAY_MS);
	}

	private boolean canAttempt() {
		if (!enabled || !resumed || !focused || destroyed || activity == null) {
			return false;
		}
		if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) {
			return false;
		}
		return !activity.isFinishing() && activity.hasWindowFocus();
	}

	private void invalidatePendingWork() {
		generation++;
		waitingLoggedGeneration = -1;
		cancelPendingRetry();
		cancelPendingVerification();
		if (pendingSurfaceRequest != null) {
			mainHandler.removeCallbacks(pendingSurfaceRequest);
			pendingSurfaceRequest = null;
		}
	}

	private void cancelPendingRetry() {
		if (pendingRetry != null) {
			mainHandler.removeCallbacks(pendingRetry);
			pendingRetry = null;
		}
	}

	private void cancelPendingVerification() {
		if (pendingVerification != null) {
			mainHandler.removeCallbacks(pendingVerification);
			pendingVerification = null;
		}
	}

	private boolean hasPendingWork() {
		return pendingRetry != null
			|| pendingSurfaceRequest != null
			|| pendingVerification != null
			|| targetSurfaceHolder != null;
	}

	private String lifecycleWaitState() {
		if (!enabled) {
			return "disabled";
		}
		if (destroyed) {
			return "destroyed";
		}
		if (!resumed) {
			return "paused";
		}
		if (!focused || activity == null || !activity.hasWindowFocus()) {
			return "unfocused";
		}
		return "activity_unavailable";
	}

	private void attachSurfaceCallback(SurfaceView surfaceView) {
		if (targetSurfaceView == surfaceView) {
			return;
		}
		detachSurfaceCallback();
		targetSurfaceView = surfaceView;
		targetSurfaceHolder = surfaceView.getHolder();
		surfaceEpoch++;
		clearLastAppliedSurface();
		if (targetSurfaceHolder != null) {
			targetSurfaceHolder.addCallback(surfaceCallback);
		}
	}

	private void detachSurfaceCallback() {
		if (targetSurfaceHolder != null) {
			try {
				targetSurfaceHolder.removeCallback(surfaceCallback);
			} catch (Throwable throwable) {
				Log.w(TAG, diagnostic("callback_detach_failed", "lifecycle", ""), throwable);
			}
		}
		targetSurfaceHolder = null;
		targetSurfaceView = null;
	}

	private void clearLastAppliedSurface() {
		lastAppliedSurface = null;
		lastAppliedSurfaceEpoch = -1;
		lastAppliedRequestMode = null;
		lastAppliedModeId = 0;
		lastAppliedRefreshRate = 0.0f;
		lastAppliedBufferWidth = 0;
		lastAppliedBufferHeight = 0;
	}

	private boolean wasAppliedToCurrentSurface(Surface surface, ModeChoice choice) {
		return lastAppliedSurface == surface
			&& lastAppliedSurfaceEpoch == surfaceEpoch
			&& lastAppliedRequestMode == choice.requestMode
			&& lastAppliedModeId == choice.modeId
			&& Math.abs(lastAppliedRefreshRate - choice.refreshRate) <= 0.01f
			&& isCurrentBufferSizeAlreadyApplied();
	}

	private boolean wasAppliedToCurrentHolderSurface() {
		if (targetSurfaceHolder == null || lastAppliedSurfaceEpoch != surfaceEpoch
			|| lastAppliedRequestMode != requestedMode) {
			return false;
		}
		try {
			Surface surface = targetSurfaceHolder.getSurface();
			return surface != null
				&& surface.isValid()
				&& surface == lastAppliedSurface
				&& isCurrentBufferSizeAlreadyApplied();
		} catch (Throwable ignored) {
			return false;
		}
	}


	private void recordAppliedBufferSize(SurfaceHolder holder) {
		try {
			Rect frame = holder == null ? null : holder.getSurfaceFrame();
			lastAppliedBufferWidth = frame == null ? 0 : frame.width();
			lastAppliedBufferHeight = frame == null ? 0 : frame.height();
		} catch (Throwable ignored) {
			lastAppliedBufferWidth = 0;
			lastAppliedBufferHeight = 0;
		}
	}

	private boolean isCurrentBufferSizeAlreadyApplied() {
		if (targetSurfaceHolder == null) {
			return false;
		}
		try {
			Rect frame = targetSurfaceHolder.getSurfaceFrame();
			int width = frame == null ? 0 : frame.width();
			int height = frame == null ? 0 : frame.height();
			if (width <= 0 || height <= 0) {
				return lastAppliedBufferWidth <= 0 || lastAppliedBufferHeight <= 0;
			}
			return width == lastAppliedBufferWidth && height == lastAppliedBufferHeight;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static void runOnUiThread(Activity activity, Runnable runnable) {
		if (activity == null || runnable == null) {
			return;
		}
		if (Looper.myLooper() == Looper.getMainLooper()) {
			runnable.run();
		} else {
			activity.runOnUiThread(runnable);
		}
	}

	private static ModeChoice chooseMode(Activity activity, RefreshRateMode requestMode) {
		RefreshRateMode effectiveMode = requestMode == null ? RefreshRateMode.SYSTEM : requestMode;
		Display display = getActivityDisplay(activity);
		if (display == null || effectiveMode == RefreshRateMode.SYSTEM) {
			return ModeChoice.unsupported(effectiveMode, 0.0f);
		}

		Display.Mode current = Build.VERSION.SDK_INT >= 23 ? display.getMode() : null;
		Display.Mode[] supportedModes = Build.VERSION.SDK_INT >= 23 ? display.getSupportedModes() : null;
		Display.Mode[] modes = supportedModes == null ? new Display.Mode[0] : supportedModes;
		int currentWidth = current == null ? 0 : current.getPhysicalWidth();
		int currentHeight = current == null ? 0 : current.getPhysicalHeight();
		int currentModeId = current == null ? 0 : current.getModeId();
		float currentModeRefreshRate = current == null ? display.getRefreshRate() : current.getRefreshRate();
		float currentRefreshRate = display.getRefreshRate();

		if (effectiveMode == RefreshRateMode.HZ60) {
			return choose60HzMode(
				current,
				modes,
				currentWidth,
				currentHeight,
				currentModeId,
				currentModeRefreshRate,
				currentRefreshRate);
		}

		int bestModeId = current == null ? 0 : current.getModeId();
		float bestRefreshRate = currentModeRefreshRate;
		int bestWidth = currentWidth;
		int bestHeight = currentHeight;
		float bestAlternativeRefreshRate = 0.0f;
		for (Display.Mode mode : modes) {
			if (mode == null || !isSamePhysicalSize(mode, currentWidth, currentHeight)) {
				continue;
			}
			float refreshRate = mode.getRefreshRate();
			if (refreshRate > bestRefreshRate + 0.01f) {
				bestRefreshRate = refreshRate;
				bestModeId = mode.getModeId();
				bestWidth = mode.getPhysicalWidth();
				bestHeight = mode.getPhysicalHeight();
			}
			bestAlternativeRefreshRate = Math.max(
				bestAlternativeRefreshRate,
				highestAlternativeRefreshRate(mode));
		}
		boolean useRefreshRateOnly = bestAlternativeRefreshRate > bestRefreshRate + 0.01f;
		float targetRefreshRate = useRefreshRateOnly ? bestAlternativeRefreshRate : bestRefreshRate;
		boolean supported = targetRefreshRate >= MIN_TARGET_HZ;
		return new ModeChoice(
			effectiveMode,
			supported,
			useRefreshRateOnly ? 0 : bestModeId,
			targetRefreshRate,
			bestWidth,
			bestHeight,
			currentRefreshRate,
			currentModeId,
			currentModeRefreshRate,
			useRefreshRateOnly ? "alternative-refresh-rate" : "exact-mode");
	}

	private static ModeChoice choose60HzMode(
		Display.Mode current,
		Display.Mode[] modes,
		int currentWidth,
		int currentHeight,
		int currentModeId,
		float currentModeRefreshRate,
		float currentRefreshRate
	) {
		Display.Mode bestExactMode = null;
		float bestExactRefreshRate = 0.0f;
		float bestExactDistance = Float.MAX_VALUE;
		float bestAlternativeRefreshRate = 0.0f;
		float bestAlternativeDistance = Float.MAX_VALUE;

		if (current != null && isWithinRefreshRate(current.getRefreshRate(), TARGET_60_HZ)) {
			bestExactMode = current;
			bestExactRefreshRate = current.getRefreshRate();
			bestExactDistance = Math.abs(bestExactRefreshRate - TARGET_60_HZ);
		}
		for (Display.Mode mode : modes) {
			if (mode == null || !isSamePhysicalSize(mode, currentWidth, currentHeight)) {
				continue;
			}
			float refreshRate = mode.getRefreshRate();
			if (isWithinRefreshRate(refreshRate, TARGET_60_HZ)) {
				float distance = Math.abs(refreshRate - TARGET_60_HZ);
				if (bestExactMode == null || distance < bestExactDistance) {
					bestExactMode = mode;
					bestExactRefreshRate = refreshRate;
					bestExactDistance = distance;
				}
			}
			float alternativeRefreshRate = closestAlternativeRefreshRate(mode, TARGET_60_HZ);
			if (alternativeRefreshRate > 0.0f) {
				float distance = Math.abs(alternativeRefreshRate - TARGET_60_HZ);
				if (distance < bestAlternativeDistance) {
					bestAlternativeRefreshRate = alternativeRefreshRate;
					bestAlternativeDistance = distance;
				}
			}
		}

		if (bestExactMode != null) {
			return new ModeChoice(
				RefreshRateMode.HZ60,
				true,
				bestExactMode.getModeId(),
				bestExactRefreshRate,
				bestExactMode.getPhysicalWidth(),
				bestExactMode.getPhysicalHeight(),
				currentRefreshRate,
				currentModeId,
				currentModeRefreshRate,
				"exact-60hz-mode");
		}
		if (bestAlternativeRefreshRate > 0.0f) {
			return new ModeChoice(
				RefreshRateMode.HZ60,
				true,
				0,
				bestAlternativeRefreshRate,
				currentWidth,
				currentHeight,
				currentRefreshRate,
				currentModeId,
				currentModeRefreshRate,
				"alternative-60hz-refresh-rate");
		}

		// On pre-M devices there is no Display.Mode API. A currently reported 60 Hz
		// display is the only capability we can safely request in that case.
		if (modes.length == 0 && isWithinRefreshRate(currentRefreshRate, TARGET_60_HZ)) {
			return new ModeChoice(
				RefreshRateMode.HZ60,
				true,
				0,
				currentRefreshRate,
				currentWidth,
				currentHeight,
				currentRefreshRate,
				currentModeId,
				currentModeRefreshRate,
				"current-display-60hz");
		}
		return ModeChoice.unsupported(RefreshRateMode.HZ60, currentRefreshRate);
	}

	private static boolean isSamePhysicalSize(Display.Mode mode, int width, int height) {
		return width <= 0 || height <= 0
			|| (mode.getPhysicalWidth() == width && mode.getPhysicalHeight() == height);
	}

	private static boolean isWithinRefreshRate(float value, float target) {
		return value > 0.0f && Math.abs(value - target) <= REFRESH_RATE_TOLERANCE_HZ;
	}

	private static boolean isModeMatched(int targetModeId, int observedModeId) {
		return targetModeId <= 0 || observedModeId == targetModeId;
	}

	private static boolean isRefreshRateMatched(
		RefreshRateMode requestMode,
		float target,
		float observedModeRefreshRate,
		float observedDisplayRefreshRate
	) {
		// Display.getRefreshRate() reflects an alternative rate selected within a
		// mode. Treat a positive display value as authoritative; only old/API-limited
		// displays that report no value fall back to the mode nominal rate.
		float observedRefreshRate = observedDisplayRefreshRate > 0.0f
			? observedDisplayRefreshRate
			: observedModeRefreshRate;
		if (requestMode == RefreshRateMode.HZ60) {
			return isWithinRefreshRate(observedRefreshRate, target);
		}
		return observedRefreshRate >= target - REFRESH_RATE_TOLERANCE_HZ;
	}

	private static float closestAlternativeRefreshRate(Display.Mode mode, float target) {
		if (mode == null || Build.VERSION.SDK_INT < 31) {
			return 0.0f;
		}
		try {
			return closestAlternativeRefreshRate(mode.getAlternativeRefreshRates(), target);
		} catch (Throwable throwable) {
			Log.w(TAG, "Unable to inspect alternative display refresh rates for mode=" + mode.getModeId(), throwable);
			return 0.0f;
		}
	}

	private static float closestAlternativeRefreshRate(float[] alternatives, float target) {
		float bestRefreshRate = 0.0f;
		float bestDistance = Float.MAX_VALUE;
		if (alternatives == null) {
			return bestRefreshRate;
		}
		for (float alternativeRefreshRate : alternatives) {
			if (!isWithinRefreshRate(alternativeRefreshRate, target)) {
				continue;
			}
			float distance = Math.abs(alternativeRefreshRate - target);
			if (distance < bestDistance) {
				bestRefreshRate = alternativeRefreshRate;
				bestDistance = distance;
			}
		}
		return bestRefreshRate;
	}

	private static float highestAlternativeRefreshRate(Display.Mode mode) {
		float bestRefreshRate = 0.0f;
		if (mode == null || Build.VERSION.SDK_INT < 31) {
			return bestRefreshRate;
		}
		try {
			for (float alternativeRefreshRate : mode.getAlternativeRefreshRates()) {
				if (alternativeRefreshRate > bestRefreshRate) {
					bestRefreshRate = alternativeRefreshRate;
				}
			}
		} catch (Throwable throwable) {
			Log.w(TAG, "Unable to inspect alternative display refresh rates for mode=" + mode.getModeId(), throwable);
		}
		return bestRefreshRate;
	}

	private static Display getActivityDisplay(Activity activity) {
		if (activity == null) {
			return null;
		}
		Display display = null;
		if (Build.VERSION.SDK_INT >= 30) {
			display = activity.getDisplay();
		}
		if (display == null) {
			WindowManager windowManager = activity.getWindowManager();
			if (windowManager != null) {
				display = windowManager.getDefaultDisplay();
			}
		}
		return display;
	}

	private static DisplayState readDisplayState(Activity activity) {
		Display display = getActivityDisplay(activity);
		if (display == null) {
			return new DisplayState(0, 0.0f, 0.0f);
		}
		Display.Mode mode = Build.VERSION.SDK_INT >= 23 ? display.getMode() : null;
		return new DisplayState(
			mode == null ? 0 : mode.getModeId(),
			mode == null ? display.getRefreshRate() : mode.getRefreshRate(),
			display.getRefreshRate());
	}

	private static WindowRequest applyWindowMode(Window window, ModeChoice choice) {
		try {
			WindowManager.LayoutParams params = window.getAttributes();
			boolean changed = false;
			if (Build.VERSION.SDK_INT >= 23 && params.preferredDisplayModeId != choice.modeId) {
				params.preferredDisplayModeId = choice.modeId;
				changed = true;
			}
			if (Build.VERSION.SDK_INT >= 21 && Math.abs(params.preferredRefreshRate - choice.refreshRate) > 0.01f) {
				params.preferredRefreshRate = choice.refreshRate;
				changed = true;
			}
			if (changed) {
				window.setAttributes(params);
			}
			String path = choice.modeId > 0 ? "exact-mode" : "refresh-rate-only";
			return new WindowRequest(changed ? path + "-set" : path + "-already-set", changed);
		} catch (Throwable throwable) {
			Log.w(TAG, "High refresh Window request failed; targetMode=" + choice.modeId
				+ "; targetHz=" + choice.refreshRate, throwable);
			return new WindowRequest("window-request-failed", false);
		}
	}

	private String resetPlatformRequest(Activity activity) {
		String windowPath = "window-unavailable";
		try {
			Window window = activity == null ? null : activity.getWindow();
			if (window != null) {
				WindowManager.LayoutParams params = window.getAttributes();
				boolean changed = false;
				if (Build.VERSION.SDK_INT >= 23 && params.preferredDisplayModeId != 0) {
					params.preferredDisplayModeId = 0;
					changed = true;
				}
				if (Build.VERSION.SDK_INT >= 21 && Math.abs(params.preferredRefreshRate) > 0.01f) {
					params.preferredRefreshRate = 0.0f;
					changed = true;
				}
				if (changed) {
					window.setAttributes(params);
				}
				windowPath = changed ? "window-cleared" : "window-already-clear";
			}
		} catch (Throwable throwable) {
			windowPath = "window-clear-failed";
			Log.w(TAG, "Unable to clear high refresh Window preferences", throwable);
		}

		String surfacePath = "surface-unavailable";
		if (Build.VERSION.SDK_INT >= 30) {
			try {
				Surface surface = null;
				if (targetSurfaceHolder != null) {
					surface = targetSurfaceHolder.getSurface();
				}
				if ((surface == null || !surface.isValid()) && activity != null) {
					View renderRoot = getRenderView(godot);
					if (renderRoot == null && activity.getWindow() != null) {
						renderRoot = activity.getWindow().getDecorView();
					}
					SurfaceView surfaceView = findFirstSurfaceView(renderRoot);
					SurfaceHolder holder = surfaceView == null ? null : surfaceView.getHolder();
					surface = holder == null ? null : holder.getSurface();
				}
				if (surface != null && surface.isValid()) {
					if (Build.VERSION.SDK_INT >= 34) {
						surface.clearFrameRate();
						surfacePath = "surface-cleared-api34";
					} else {
						// clearFrameRate() was added in API 34. API 30-33 use the
						// documented zero-rate vote to release the Surface preference.
						surface.setFrameRate(0.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
						surfacePath = "surface-cleared-api30";
					}
				}
			} catch (Throwable throwable) {
				surfacePath = "surface-clear-failed";
				Log.w(TAG, "Unable to clear high refresh Surface vote", throwable);
			}
		} else {
			surfacePath = "surface-api-unavailable";
		}
		return windowPath + "," + surfacePath;
	}

	private static View getRenderView(Godot godot) {
		if (godot == null) {
			return null;
		}
		try {
			GodotRenderView renderView = godot.getRenderView();
			return renderView == null ? null : renderView.getView();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static SurfaceView findFirstSurfaceView(View view) {
		if (view == null) {
			return null;
		}
		if (view instanceof SurfaceView) {
			return (SurfaceView) view;
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int index = 0; index < group.getChildCount(); index++) {
				SurfaceView surfaceView = findFirstSurfaceView(group.getChildAt(index));
				if (surfaceView != null) {
					return surfaceView;
				}
			}
		}
		return null;
	}

	private static String applySurfaceFrameRate(Surface surface, float refreshRate) {
		if (Build.VERSION.SDK_INT < 30) {
			return "surface-api-unavailable";
		}
		try {
			if (surface == null || !surface.isValid()) {
				return "surface-invalid";
			}
			if (Build.VERSION.SDK_INT >= 31) {
				surface.setFrameRate(
					refreshRate,
					Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
					Surface.CHANGE_FRAME_RATE_ALWAYS);
			} else {
				surface.setFrameRate(refreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
			}
			return Build.VERSION.SDK_INT >= 31 ? "surface-always" : "surface";
		} catch (Throwable throwable) {
			Log.w(TAG, "High refresh Surface.setFrameRate failed; Window refresh request remains active", throwable);
			return "surface-failed";
		}
	}

	private String diagnostic(String state, String reason, String details) {
		String suffix = details == null || details.isEmpty() ? "" : "; " + details;
		return "HighRefresh{state=" + state
			+ "; generation=" + generation
			+ "; surfaceEpoch=" + surfaceEpoch
			+ "; resumed=" + resumed
			+ "; focused=" + focused
			+ "; reason=" + reason
			+ suffix
			+ "}";
	}

	private static final class ModeChoice {
		final RefreshRateMode requestMode;
		final boolean supported;
		final int modeId;
		final float refreshRate;
		final int width;
		final int height;
		final float currentRefreshRate;
		final int currentModeId;
		final float currentModeRefreshRate;
		final String selectionPath;

		static ModeChoice unsupported(RefreshRateMode requestMode, float currentRefreshRate) {
			return new ModeChoice(
				requestMode,
				false,
				0,
				0.0f,
				0,
				0,
				currentRefreshRate,
				0,
				currentRefreshRate,
				"unsupported");
		}

		ModeChoice(
			RefreshRateMode requestMode,
			boolean supported,
			int modeId,
			float refreshRate,
			int width,
			int height,
			float currentRefreshRate,
			int currentModeId,
			float currentModeRefreshRate,
			String selectionPath
		) {
			this.requestMode = requestMode;
			this.supported = supported;
			this.modeId = modeId;
			this.refreshRate = refreshRate;
			this.width = width;
			this.height = height;
			this.currentRefreshRate = currentRefreshRate;
			this.currentModeId = currentModeId;
			this.currentModeRefreshRate = currentModeRefreshRate;
			this.selectionPath = selectionPath;
		}
	}

	private static final class DisplayState {
		final int modeId;
		final float modeRefreshRate;
		final float displayRefreshRate;

		DisplayState(int modeId, float modeRefreshRate, float displayRefreshRate) {
			this.modeId = modeId;
			this.modeRefreshRate = modeRefreshRate;
			this.displayRefreshRate = displayRefreshRate;
		}
	}

	private static final class WindowRequest {
		final String path;
		final boolean changed;

		WindowRequest(String path, boolean changed) {
			this.path = path;
			this.changed = changed;
		}
	}
}
