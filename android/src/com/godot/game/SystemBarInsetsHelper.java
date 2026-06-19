package com.godot.game;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

final class SystemBarInsetsHelper {
	private SystemBarInsetsHelper() {
	}

	static void enableEdgeToEdge(Activity activity) {
		if (activity == null) {
			return;
		}
		Window window = activity.getWindow();
		if (window == null) {
			return;
		}
		WindowCompat.setDecorFitsSystemWindows(window, false);
		window.setStatusBarColor(Color.TRANSPARENT);
		window.setNavigationBarColor(Color.TRANSPARENT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			window.setStatusBarContrastEnforced(false);
			window.setNavigationBarContrastEnforced(false);
		}
		WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
		controller.setAppearanceLightStatusBars(false);
		controller.setAppearanceLightNavigationBars(false);
	}

	static void applySystemBarPadding(View view, boolean top, boolean right, boolean bottom, boolean left) {
		applySystemBarPadding(view, top, right, bottom, left, false);
	}

	static void applySystemBarPaddingAndGrow(View view, boolean top, boolean right, boolean bottom, boolean left) {
		applySystemBarPadding(view, top, right, bottom, left, true);
	}

	private static void applySystemBarPadding(View view, boolean top, boolean right, boolean bottom, boolean left, boolean growHeight) {
		if (view == null) {
			return;
		}

		int originalLeft = view.getPaddingLeft();
		int originalTop = view.getPaddingTop();
		int originalRight = view.getPaddingRight();
		int originalBottom = view.getPaddingBottom();
		int originalMinHeight = view.getMinimumHeight();
		ViewGroup.LayoutParams originalLayoutParams = view.getLayoutParams();
		int originalLayoutHeight = originalLayoutParams == null ? ViewGroup.LayoutParams.WRAP_CONTENT : originalLayoutParams.height;
		InsetsHolder insetsHolder = new InsetsHolder();

		Runnable updatePadding = () -> {
			InsetsHolder overlap = calculateOverlap(view, insetsHolder);
			int extraTop = top ? overlap.top : 0;
			int extraRight = right ? overlap.right : 0;
			int extraBottom = bottom ? overlap.bottom : 0;
			int extraLeft = left ? overlap.left : 0;

			int desiredLeft = originalLeft + extraLeft;
			int desiredTop = originalTop + extraTop;
			int desiredRight = originalRight + extraRight;
			int desiredBottom = originalBottom + extraBottom;
			if (view.getPaddingLeft() != desiredLeft
				|| view.getPaddingTop() != desiredTop
				|| view.getPaddingRight() != desiredRight
				|| view.getPaddingBottom() != desiredBottom) {
				view.setPadding(desiredLeft, desiredTop, desiredRight, desiredBottom);
			}

			if (growHeight) {
				int extraHeight = extraTop + extraBottom;
				int desiredMinHeight = originalMinHeight + extraHeight;
				if (view.getMinimumHeight() != desiredMinHeight) {
					view.setMinimumHeight(desiredMinHeight);
				}
				ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
				if (layoutParams != null && originalLayoutHeight > 0) {
					int desiredHeight = originalLayoutHeight + extraHeight;
					if (layoutParams.height != desiredHeight) {
						layoutParams.height = desiredHeight;
						view.setLayoutParams(layoutParams);
					}
				}
			}
		};

		ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
			Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			insetsHolder.left = insets.left;
			insetsHolder.top = insets.top;
			insetsHolder.right = insets.right;
			insetsHolder.bottom = insets.bottom;
			updatePadding.run();
			return windowInsets;
		});
		view.addOnLayoutChangeListener((target, leftValue, topValue, rightValue, bottomValue, oldLeft, oldTop, oldRight, oldBottom) -> updatePadding.run());
		view.post(() -> {
			updatePadding.run();
			ViewCompat.requestApplyInsets(view);
		});
	}

	private static InsetsHolder calculateOverlap(View view, InsetsHolder systemBars) {
		InsetsHolder overlap = new InsetsHolder();
		if (view == null || systemBars == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
			return overlap;
		}
		View rootView = view.getRootView();
		if (rootView == null || rootView.getWidth() <= 0 || rootView.getHeight() <= 0) {
			return overlap;
		}

		int[] viewLocation = new int[2];
		int[] rootLocation = new int[2];
		view.getLocationInWindow(viewLocation);
		rootView.getLocationInWindow(rootLocation);

		int viewLeft = viewLocation[0];
		int viewTop = viewLocation[1];
		int viewRight = viewLeft + view.getWidth();
		int viewBottom = viewTop + view.getHeight();
		int safeLeft = rootLocation[0] + systemBars.left;
		int safeTop = rootLocation[1] + systemBars.top;
		int safeRight = rootLocation[0] + rootView.getWidth() - systemBars.right;
		int safeBottom = rootLocation[1] + rootView.getHeight() - systemBars.bottom;

		overlap.left = Math.max(0, safeLeft - viewLeft);
		overlap.top = Math.max(0, safeTop - viewTop);
		overlap.right = Math.max(0, viewRight - safeRight);
		overlap.bottom = Math.max(0, viewBottom - safeBottom);
		return overlap;
	}

	private static final class InsetsHolder {
		int left;
		int top;
		int right;
		int bottom;
	}
}
