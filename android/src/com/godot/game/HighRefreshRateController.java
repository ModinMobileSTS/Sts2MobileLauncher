package com.godot.game;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotRenderView;

import java.lang.reflect.Method;

final class HighRefreshRateController {
	private static final String TAG = "Sts2Re";
	private static final float MIN_TARGET_HZ = 61.0f;

	private HighRefreshRateController() {
	}

	static void apply(Activity activity, Godot godot, String reason) {
		if (activity == null) {
			return;
		}
		activity.runOnUiThread(() -> applyOnUiThread(activity, godot, reason));
	}

	static void applyWithRetries(Activity activity, Godot godot, String reason) {
		apply(activity, godot, reason);
		if (activity == null) {
			return;
		}
		activity.runOnUiThread(() -> {
			View decorView = null;
			Window window = activity.getWindow();
			if (window != null) {
				decorView = window.getDecorView();
			}
			if (decorView == null) {
				return;
			}
			decorView.postDelayed(() -> apply(activity, godot, reason + "/retry250ms"), 250);
			decorView.postDelayed(() -> apply(activity, godot, reason + "/retry1000ms"), 1000);
			decorView.postDelayed(() -> apply(activity, godot, reason + "/retry2500ms"), 2500);
		});
	}

	private static void applyOnUiThread(Activity activity, Godot godot, String reason) {
		try {
			Window window = activity.getWindow();
			if (window == null) {
				Log.w(TAG, "High refresh skipped: no window; reason=" + reason);
				return;
			}
			ModeChoice choice = chooseBestMode(activity);
			if (choice.refreshRate < MIN_TARGET_HZ) {
				Log.w(TAG, "High refresh skipped: no >60Hz display mode; reason=" + reason + "; " + choice);
				return;
			}
			applyWindowMode(window, choice, reason);
			applyViewFrameRate(window.getDecorView(), choice.refreshRate, reason + "/decor");
			View renderView = getRenderView(godot);
			if (renderView != null && renderView != window.getDecorView()) {
				applyViewFrameRate(renderView, choice.refreshRate, reason + "/godot");
			}
			int surfaceViewCount = countSurfaceViews(window.getDecorView());
			Log.i(TAG, "High refresh scan reason=" + reason + "; surfaceViewCount=" + surfaceViewCount);
		} catch (Throwable throwable) {
			Log.w(TAG, "High refresh apply failed; reason=" + reason, throwable);
		}
	}

	private static ModeChoice chooseBestMode(Activity activity) {
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
		if (display == null) {
			return new ModeChoice(0, 0.0f, 0, 0);
		}

		Display.Mode current = Build.VERSION.SDK_INT >= 23 ? display.getMode() : null;
		Display.Mode[] modes = Build.VERSION.SDK_INT >= 23 ? display.getSupportedModes() : new Display.Mode[0];
		StringBuilder modeList = new StringBuilder();
		int currentWidth = current == null ? 0 : current.getPhysicalWidth();
		int currentHeight = current == null ? 0 : current.getPhysicalHeight();
		int bestModeId = current == null ? 0 : current.getModeId();
		float bestRefreshRate = current == null ? display.getRefreshRate() : current.getRefreshRate();
		int bestWidth = currentWidth;
		int bestHeight = currentHeight;

		for (Display.Mode mode : modes) {
			if (mode == null) {
				continue;
			}
			if (modeList.length() > 0) {
				modeList.append(", ");
			}
			modeList.append('#')
				.append(mode.getModeId())
				.append(':')
				.append(mode.getPhysicalWidth())
				.append('x')
				.append(mode.getPhysicalHeight())
				.append('@')
				.append(mode.getRefreshRate());
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
		}
		return new ModeChoice(bestModeId, bestRefreshRate, bestWidth, bestHeight, display.getRefreshRate(), modeList.toString());
	}

	private static void applyWindowMode(Window window, ModeChoice choice, String reason) {
		WindowManager.LayoutParams params = window.getAttributes();
		boolean changed = false;
		if (choice.modeId > 0 && params.preferredDisplayModeId != choice.modeId) {
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
		Log.i(TAG, "High refresh window request reason=" + reason + "; changed=" + changed + "; " + choice);
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

	private static void applyViewFrameRate(View view, float refreshRate, String reason) {
		if (view == null) {
			return;
		}
		try {
			applyRequestedFrameRate(view, refreshRate);
			if (view instanceof SurfaceView) {
				applySurfaceViewFrameRate((SurfaceView) view, refreshRate, reason);
			}
			if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				for (int index = 0; index < group.getChildCount(); index++) {
					applyViewFrameRate(group.getChildAt(index), refreshRate, reason + "/child" + index);
				}
			}
			Log.i(TAG, "High refresh view request reason=" + reason + "; hz=" + refreshRate + "; view=" + view.getClass().getName());
		} catch (Throwable throwable) {
			Log.w(TAG, "High refresh view request failed reason=" + reason, throwable);
		}
	}

	private static int countSurfaceViews(View view) {
		if (view == null) {
			return 0;
		}
		int count = view instanceof SurfaceView ? 1 : 0;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int index = 0; index < group.getChildCount(); index++) {
				count += countSurfaceViews(group.getChildAt(index));
			}
		}
		return count;
	}

	private static void applyRequestedFrameRate(View view, float refreshRate) {
		try {
			Method method = View.class.getMethod("setRequestedFrameRate", float.class);
			method.invoke(view, refreshRate);
		} catch (NoSuchMethodException ignored) {
			// Added on newer Android releases; Surface/Window requests below cover older devices.
		} catch (Throwable throwable) {
			Log.w(TAG, "High refresh setRequestedFrameRate failed view=" + view.getClass().getName(), throwable);
		}
	}

	private static void applySurfaceViewFrameRate(SurfaceView surfaceView, float refreshRate, String reason) {
		boolean surfaceRequested = false;
		boolean controlRequested = false;
		try {
			SurfaceHolder holder = surfaceView.getHolder();
			Surface surface = holder == null ? null : holder.getSurface();
			if (Build.VERSION.SDK_INT >= 30 && surface != null && surface.isValid()) {
				if (Build.VERSION.SDK_INT >= 31) {
					surface.setFrameRate(refreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS);
				} else {
					surface.setFrameRate(refreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
				}
				surfaceRequested = true;
			}
		} catch (Throwable throwable) {
			Log.w(TAG, "High refresh Surface.setFrameRate failed reason=" + reason, throwable);
		}

		try {
			if (Build.VERSION.SDK_INT >= 29) {
				SurfaceControl surfaceControl = surfaceView.getSurfaceControl();
				if (surfaceControl != null && surfaceControl.isValid()) {
					SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
					if (Build.VERSION.SDK_INT >= 31) {
						transaction.setFrameRate(surfaceControl, refreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS);
					} else if (Build.VERSION.SDK_INT >= 30) {
						transaction.setFrameRate(surfaceControl, refreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
					}
					transaction.apply();
					controlRequested = true;
				}
			}
		} catch (Throwable throwable) {
			Log.w(TAG, "High refresh SurfaceControl.Transaction.setFrameRate failed reason=" + reason, throwable);
		}

		Log.i(TAG, "High refresh render surface request reason=" + reason + "; hz=" + refreshRate + "; surface=" + surfaceRequested + "; surfaceControl=" + controlRequested + "; view=" + surfaceView.getClass().getName());
	}

	private static final class ModeChoice {
		final int modeId;
		final float refreshRate;
		final int width;
		final int height;
		final float currentRefreshRate;
		final String supportedModes;

		ModeChoice(int modeId, float refreshRate, int width, int height) {
			this(modeId, refreshRate, width, height, 0.0f, "");
		}

		ModeChoice(int modeId, float refreshRate, int width, int height, float currentRefreshRate, String supportedModes) {
			this.modeId = modeId;
			this.refreshRate = refreshRate;
			this.width = width;
			this.height = height;
			this.currentRefreshRate = currentRefreshRate;
			this.supportedModes = supportedModes;
		}

		@Override
		public String toString() {
			return "modeId=" + modeId
				+ "; refreshRate=" + refreshRate
				+ "; size=" + width + "x" + height
				+ "; currentRefreshRate=" + currentRefreshRate
				+ "; supportedModes=[" + supportedModes + "]";
		}
	}
}
