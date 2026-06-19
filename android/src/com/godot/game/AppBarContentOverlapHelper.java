package com.godot.game;

import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

final class AppBarContentOverlapHelper {
	private AppBarContentOverlapHelper() {
	}

	static void install(AppCompatActivity activity) {
		ViewGroup contentView = activity.findViewById(android.R.id.content);
		if (contentView == null || contentView.getChildCount() == 0) {
			return;
		}
		View contentRoot = contentView.getChildAt(0);
		if (contentRoot == null) {
			return;
		}

		int originalLeft = contentRoot.getPaddingLeft();
		int originalTop = contentRoot.getPaddingTop();
		int originalRight = contentRoot.getPaddingRight();
		int originalBottom = contentRoot.getPaddingBottom();
		int[] systemBars = new int[4];

		View[] insetAdjustedActionBar = new View[1];
		Runnable[] updatePadding = new Runnable[1];
		updatePadding[0] = () -> {
			// Android 15+ may place the support ActionBar and content under system bars on some OEM builds.
			// Measure the actual overlap instead of assuming a fixed action bar or status bar height.
			View actionBarContainer = activity.getWindow().getDecorView().findViewById(androidx.appcompat.R.id.action_bar_container);
			if (actionBarContainer != null && insetAdjustedActionBar[0] != actionBarContainer) {
				SystemBarInsetsHelper.applySystemBarPaddingAndGrow(actionBarContainer, true, false, false, false);
				actionBarContainer.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> contentRoot.post(updatePadding[0]));
				insetAdjustedActionBar[0] = actionBarContainer;
			}
			int actionBarOverlapTop = calculateTopOverlap(contentRoot, actionBarContainer);
			int[] systemOverlap = calculateSystemBarOverlap(contentRoot, systemBars);
			int desiredLeft = originalLeft + systemOverlap[0];
			int desiredTop = originalTop + Math.max(actionBarOverlapTop, systemOverlap[1]);
			int desiredRight = originalRight + systemOverlap[2];
			int desiredBottom = originalBottom + systemOverlap[3];
			if (contentRoot.getPaddingLeft() != desiredLeft
				|| contentRoot.getPaddingTop() != desiredTop
				|| contentRoot.getPaddingRight() != desiredRight
				|| contentRoot.getPaddingBottom() != desiredBottom) {
				contentRoot.setPadding(desiredLeft, desiredTop, desiredRight, desiredBottom);
			}
		};

		ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (target, windowInsets) -> {
			Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			systemBars[0] = insets.left;
			systemBars[1] = insets.top;
			systemBars[2] = insets.right;
			systemBars[3] = insets.bottom;
			updatePadding[0].run();
			return windowInsets;
		});
		View decorView = activity.getWindow().getDecorView();
		decorView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updatePadding[0].run());
		contentRoot.post(() -> {
			updatePadding[0].run();
			ViewCompat.requestApplyInsets(contentRoot);
		});
	}

	private static int calculateTopOverlap(View contentRoot, View actionBarContainer) {
		if (contentRoot == null || actionBarContainer == null || !actionBarContainer.isShown()) {
			return 0;
		}
		if (contentRoot.getHeight() <= 0 || actionBarContainer.getHeight() <= 0) {
			return 0;
		}
		int[] contentLocation = new int[2];
		int[] actionBarLocation = new int[2];
		contentRoot.getLocationInWindow(contentLocation);
		actionBarContainer.getLocationInWindow(actionBarLocation);
		return Math.max(0, actionBarLocation[1] + actionBarContainer.getHeight() - contentLocation[1]);
	}

	private static int[] calculateSystemBarOverlap(View view, int[] systemBars) {
		int[] overlap = new int[4];
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
		int safeLeft = rootLocation[0] + systemBars[0];
		int safeTop = rootLocation[1] + systemBars[1];
		int safeRight = rootLocation[0] + rootView.getWidth() - systemBars[2];
		int safeBottom = rootLocation[1] + rootView.getHeight() - systemBars[3];

		overlap[0] = Math.max(0, safeLeft - viewLeft);
		overlap[1] = Math.max(0, safeTop - viewTop);
		overlap[2] = Math.max(0, viewRight - safeRight);
		overlap[3] = Math.max(0, viewBottom - safeBottom);
		return overlap;
	}
}
