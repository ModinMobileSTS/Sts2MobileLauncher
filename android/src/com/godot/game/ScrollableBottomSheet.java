package com.godot.game;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Content-height sheets with one bounded viewport and a fixed, handle-only dismiss target. */
final class ScrollableBottomSheet {
	private ScrollableBottomSheet() {
	}

	static void setContent(BottomSheetDialog dialog, LinearLayout content) {
		Context context = dialog.getContext();
		LinearLayout root = ExtraSettingsUi.vertical(context);
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		float radius = ExtraSettingsUi.dp(context, 28);
		background.setCornerRadii(new float[] { radius, radius, radius, radius, 0, 0, 0, 0 });
		root.setBackground(background);
		root.addView(dismissHandle(dialog), new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 48)));

		NestedScrollView scroll = new NestedScrollView(context);
		scroll.setFillViewport(false);
		// This viewport owns body gestures even at its top/bottom. Do not hand an
		// unconsumed scroll/fling to BottomSheetBehavior and accidentally dismiss.
		scroll.setNestedScrollingEnabled(false);
		content.setBackground(null);
		scroll.addView(content, new NestedScrollView.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		root.addView(scroll, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		dialog.setContentView(root, new ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
		behavior.setFitToContents(true);
		behavior.setSkipCollapsed(true);
		behavior.setDraggable(false);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		Window window = dialog.getWindow();
		if (window != null) {
			window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		}
	}

	private static View dismissHandle(BottomSheetDialog dialog) {
		Context context = dialog.getContext();
		FrameLayout target = new FrameLayout(context);
		View handle = new View(context);
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_OUTLINE);
		background.setCornerRadius(ExtraSettingsUi.dp(context, 2));
		handle.setBackground(background);
		target.addView(handle, new FrameLayout.LayoutParams(
			ExtraSettingsUi.dp(context, 32), ExtraSettingsUi.dp(context, 4), Gravity.CENTER));
		target.setContentDescription(context.getString(android.R.string.cancel));
		target.setFocusable(true);
		target.setOnClickListener(view -> dialog.cancel());
		int dismissDistance = ExtraSettingsUi.dp(context, 36);
		int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
		target.setOnTouchListener(new View.OnTouchListener() {
			private float downX;
			private float downY;
			private boolean active;
			private boolean moved;

			@Override public boolean onTouch(View view, MotionEvent event) {
				switch (event.getActionMasked()) {
					case MotionEvent.ACTION_DOWN:
						downX = event.getRawX();
						downY = event.getRawY();
						active = true;
						moved = false;
						return true;
					case MotionEvent.ACTION_MOVE:
						moved |= Math.abs(event.getRawX() - downX) > touchSlop
							|| Math.abs(event.getRawY() - downY) > touchSlop;
						return true;
					case MotionEvent.ACTION_UP:
						if (active) {
							float dx = Math.abs(event.getRawX() - downX);
							float dy = event.getRawY() - downY;
							if (dy >= dismissDistance && dy > dx) {
								dialog.cancel();
							} else if (!moved && dx <= touchSlop && Math.abs(dy) <= touchSlop) {
								view.performClick();
							}
						}
						active = false;
						return true;
					case MotionEvent.ACTION_POINTER_DOWN:
					case MotionEvent.ACTION_CANCEL:
						active = false;
						return true;
					default:
						return true;
				}
			}
		});
		return target;
	}
}
