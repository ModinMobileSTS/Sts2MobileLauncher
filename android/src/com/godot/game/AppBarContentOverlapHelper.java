package com.godot.game;

import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;

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

		Runnable updatePadding = () -> {
			// Android 15+ may place the content view under the support ActionBar on some OEM builds.
			// Measure the actual overlap instead of assuming a fixed action bar height.
			View actionBarContainer = activity.getWindow().getDecorView().findViewById(androidx.appcompat.R.id.action_bar_container);
			int overlapTop = calculateTopOverlap(contentRoot, actionBarContainer);
			int desiredTop = originalTop + overlapTop;
			if (contentRoot.getPaddingLeft() != originalLeft
				|| contentRoot.getPaddingTop() != desiredTop
				|| contentRoot.getPaddingRight() != originalRight
				|| contentRoot.getPaddingBottom() != originalBottom) {
				contentRoot.setPadding(originalLeft, desiredTop, originalRight, originalBottom);
			}
		};

		View decorView = activity.getWindow().getDecorView();
		decorView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updatePadding.run());
		contentRoot.post(updatePadding);
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
}
