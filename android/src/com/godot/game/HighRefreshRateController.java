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
	private static final long[] RETRY_DELAYS_MS = {100L, 500L, 1500L};
	private static final long VERIFY_DELAY_MS = 1200L;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private Activity activity;
	private Godot godot;
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

	void request(Activity activity, Godot godot, String reason) {
		runOnUiThread(activity, () -> {
			if (destroyed) {
				return;
			}
			this.activity = activity;
			this.godot = godot;
			enabled = true;
			startGeneration(reason);
		});
	}

	void disable(Activity activity, String reason) {
		runOnUiThread(activity, () -> {
			boolean wasEnabled = enabled;
			enabled = false;
			invalidatePendingWork();
			String resetPath = resetPlatformRequest(activity);
			detachSurfaceCallback();
			clearLastAppliedSurface();
			if (wasEnabled) {
				Log.i(TAG, diagnostic("disabled", reason, "reset=" + resetPath));
			}
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

			ModeChoice choice = chooseBestMode(activity);
			if (choice.refreshRate < MIN_TARGET_HZ) {
				Log.i(TAG, diagnostic("unsupported", reason, "displayHz=" + choice.currentRefreshRate));
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
					"targetMode=" + choice.modeId
						+ "; targetHz=" + choice.refreshRate
						+ "; window=" + windowRequest.path
						+ "; surface=" + surfacePath));
				return;
			}
			lastAppliedSurface = surface;
			lastAppliedSurfaceEpoch = surfaceEpoch;
			lastAppliedModeId = choice.modeId;
			lastAppliedRefreshRate = choice.refreshRate;
			recordAppliedBufferSize(holder);
			cancelPendingRetry();
			scheduleVerification(expectedGeneration, reason, choice);
			Log.i(TAG, diagnostic("applied", reason,
				"attempt=" + attempt
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
			boolean modeMatched = choice.modeId <= 0 || observed.modeId == choice.modeId;
			boolean refreshMatched = observed.modeRefreshRate >= choice.refreshRate - 0.5f
				|| observed.displayRefreshRate >= choice.refreshRate - 0.5f;
			Log.i(TAG, diagnostic(refreshMatched ? "verified" : "verification_mismatch", reason,
				"targetMode=" + choice.modeId
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
		lastAppliedModeId = 0;
		lastAppliedRefreshRate = 0.0f;
		lastAppliedBufferWidth = 0;
		lastAppliedBufferHeight = 0;
	}

	private boolean wasAppliedToCurrentSurface(Surface surface, ModeChoice choice) {
		return lastAppliedSurface == surface
			&& lastAppliedSurfaceEpoch == surfaceEpoch
			&& lastAppliedModeId == choice.modeId
			&& Math.abs(lastAppliedRefreshRate - choice.refreshRate) <= 0.01f
			&& isCurrentBufferSizeAlreadyApplied();
	}

	private boolean wasAppliedToCurrentHolderSurface() {
		if (targetSurfaceHolder == null || lastAppliedSurfaceEpoch != surfaceEpoch) {
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

	private static ModeChoice chooseBestMode(Activity activity) {
		Display display = getActivityDisplay(activity);
		if (display == null) {
			return new ModeChoice(0, 0.0f, 0, 0);
		}

		Display.Mode current = Build.VERSION.SDK_INT >= 23 ? display.getMode() : null;
		Display.Mode[] modes = Build.VERSION.SDK_INT >= 23 ? display.getSupportedModes() : new Display.Mode[0];
		int currentWidth = current == null ? 0 : current.getPhysicalWidth();
		int currentHeight = current == null ? 0 : current.getPhysicalHeight();
		int currentModeId = current == null ? 0 : current.getModeId();
		float currentModeRefreshRate = current == null ? display.getRefreshRate() : current.getRefreshRate();
		int bestModeId = current == null ? 0 : current.getModeId();
		float bestRefreshRate = currentModeRefreshRate;
		int bestWidth = currentWidth;
		int bestHeight = currentHeight;
		float bestAlternativeRefreshRate = bestRefreshRate;

		for (Display.Mode mode : modes) {
			if (mode == null) {
				continue;
			}
			boolean sameSize = currentWidth <= 0 || currentHeight <= 0
				|| (mode.getPhysicalWidth() == currentWidth && mode.getPhysicalHeight() == currentHeight);
			if (!sameSize) {
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
		return new ModeChoice(
			useRefreshRateOnly ? 0 : bestModeId,
			useRefreshRateOnly ? bestAlternativeRefreshRate : bestRefreshRate,
			bestWidth,
			bestHeight,
			display.getRefreshRate(),
			currentModeId,
			currentModeRefreshRate,
			useRefreshRateOnly ? "alternative-refresh-rate" : "exact-mode");
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
					surface.clearFrameRate();
					surfacePath = "surface-cleared";
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
		final int modeId;
		final float refreshRate;
		final int width;
		final int height;
		final float currentRefreshRate;
		final int currentModeId;
		final float currentModeRefreshRate;
		final String selectionPath;

		ModeChoice(int modeId, float refreshRate, int width, int height) {
			this(modeId, refreshRate, width, height, 0.0f, 0, 0.0f, "unavailable");
		}

		ModeChoice(
			int modeId,
			float refreshRate,
			int width,
			int height,
			float currentRefreshRate,
			int currentModeId,
			float currentModeRefreshRate,
			String selectionPath
		) {
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
