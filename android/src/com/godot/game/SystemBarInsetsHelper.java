package com.godot.game;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.WeakHashMap;

/**
 * Edge-to-edge helpers for launcher/tool activities.
 *
 * <p>Insets are applied as direct {@link WindowInsetsCompat} values on scaffold edges
 * (top chrome, bottom nav, side rail, scroll content). Geometry-based "overlap" padding
 * is intentionally not used — it feedback-loops with layout and is unreliable on
 * cutout / multi-window / OEM builds.
 */
final class SystemBarInsetsHelper {
	private static final int INSET_TYPE_MASK =
		WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();

	private static final WeakHashMap<View, BasePadding> BASE_PADDING = new WeakHashMap<>();

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

	/**
	 * Apply system bar + display cutout insets as extra padding on selected sides.
	 * Base padding is captured once from the view's current padding when first installed.
	 * <p>
	 * Prefer {@code wrap_content} (or content-sized minHeight without inset) for top chrome so
	 * top padding alone grows the bar under the status bar / cutout.
	 */
	static void applySystemBarPadding(View view, boolean top, boolean right, boolean bottom, boolean left) {
		applySystemBarPadding(view, top, right, bottom, left, false);
	}

	/**
	 * Same as {@link #applySystemBarPadding(View, boolean, boolean, boolean, boolean)}.
	 * The former "grow height" path mutated LayoutParams from geometry overlap and is removed;
	 * top/bottom padding on wrap_content views is sufficient.
	 */
	static void applySystemBarPaddingAndGrow(View view, boolean top, boolean right, boolean bottom, boolean left) {
		applySystemBarPadding(view, top, right, bottom, left, false);
	}

	/**
	 * Apply system bars/cutout on selected sides, and use {@code max(navigationBars, ime)} for bottom
	 * when {@code bottom} is true so soft keyboards clear content under edge-to-edge + adjustResize.
	 */
	static void applySystemBarPaddingWithIme(View view, boolean top, boolean right, boolean bottom, boolean left) {
		applySystemBarPadding(view, top, right, bottom, left, true);
	}

	private static void applySystemBarPadding(
		View view,
		boolean top,
		boolean right,
		boolean bottom,
		boolean left,
		boolean bottomIncludesIme
	) {
		if (view == null) {
			return;
		}
		if (!(top || right || bottom || left)) {
			return;
		}

		BasePadding base = BASE_PADDING.get(view);
		if (base == null) {
			base = new BasePadding(
				view.getPaddingLeft(),
				view.getPaddingTop(),
				view.getPaddingRight(),
				view.getPaddingBottom()
			);
			BASE_PADDING.put(view, base);
		}

		final BasePadding basePadding = base;
		ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
			Insets systemInsets = windowInsets.getInsets(INSET_TYPE_MASK);
			int extraLeft = left ? systemInsets.left : 0;
			int extraTop = top ? systemInsets.top : 0;
			int extraRight = right ? systemInsets.right : 0;
			int extraBottom = 0;
			if (bottom) {
				if (bottomIncludesIme) {
					Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
					extraBottom = Math.max(systemInsets.bottom, imeInsets.bottom);
				} else {
					extraBottom = systemInsets.bottom;
				}
			}

			int desiredLeft = basePadding.left + extraLeft;
			int desiredTop = basePadding.top + extraTop;
			int desiredRight = basePadding.right + extraRight;
			int desiredBottom = basePadding.bottom + extraBottom;
			if (target.getPaddingLeft() != desiredLeft
				|| target.getPaddingTop() != desiredTop
				|| target.getPaddingRight() != desiredRight
				|| target.getPaddingBottom() != desiredBottom) {
				target.setPadding(desiredLeft, desiredTop, desiredRight, desiredBottom);
			}
			// Do not consume: multiple scaffold edges (top bar + bottom nav + content) each need the same insets.
			return windowInsets;
		});
		ViewCompat.requestApplyInsets(view);
	}

	private static final class BasePadding {
		final int left;
		final int top;
		final int right;
		final int bottom;

		BasePadding(int left, int top, int right, int bottom) {
			this.left = left;
			this.top = top;
			this.right = right;
			this.bottom = bottom;
		}
	}
}
