package com.godot.game;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Keeps the log viewport exact even when HorizontalScrollView probes with UNSPECIFIED. */
public final class LogViewportCanvas extends FrameLayout {
	public LogViewportCanvas(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int width = getLayoutParams().width;
		if (width <= 0) {
			View viewport = (View) getParent();
			width = Math.max(1, viewport.getMeasuredWidth() - viewport.getPaddingLeft() - viewport.getPaddingRight());
		}
		super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
	}
}
