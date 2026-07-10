package com.godot.game.steam.ui;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import com.godot.game.ExtraSettingsUi;

import java.util.ArrayList;

/**
 * Transfer-rate sparkline with smooth display interpolation.
 * Incoming samples are treated as targets; the drawn curve lerps toward them
 * with an ease-out cubic so the line does not jump frame-to-frame.
 */
public final class SteamSpeedSparklineView extends View {
	private static final int MAX_SAMPLES = 36;
	/** Per-frame approach factor after ease (higher = snappier). */
	private static final float BASE_LERP = 0.06f;
	private static final long FRAME_MS = 16L;

	private final ArrayList<Float> targetSamples = new ArrayList<>(MAX_SAMPLES);
	private final ArrayList<Float> displaySamples = new ArrayList<>(MAX_SAMPLES);
	private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path fillPath = new Path();
	private final Path linePath = new Path();
	private final TimeInterpolator easeOut = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
	private final Runnable frameTick = this::onFrame;

	private float displayPeak;
	private float targetPeak;
	private boolean animating;
	private long lastFrameAtMs;

	public SteamSpeedSparklineView(Context context) {
		this(context, null);
	}

	public SteamSpeedSparklineView(Context context, AttributeSet attrs) {
		super(context, attrs);
		linePaint.setStyle(Paint.Style.STROKE);
		linePaint.setStrokeWidth(ExtraSettingsUi.dp(context, 1.8f));
		linePaint.setColor(ExtraSettingsUi.COLOR_PRIMARY);
		linePaint.setStrokeJoin(Paint.Join.ROUND);
		linePaint.setStrokeCap(Paint.Cap.ROUND);
		fillPaint.setStyle(Paint.Style.FILL);
		baselinePaint.setStyle(Paint.Style.STROKE);
		baselinePaint.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
		baselinePaint.setColor(ColorWithAlpha(ExtraSettingsUi.COLOR_OUTLINE, 90));
		setMinimumHeight(ExtraSettingsUi.dp(context, 96));
	}

	public void clearSamples() {
		targetSamples.clear();
		displaySamples.clear();
		displayPeak = 0f;
		targetPeak = 0f;
		stopAnimation();
		invalidate();
	}

	/**
	 * Push a new chart point (already window-averaged by the panel).
	 * Display values ease toward the new target rather than snapping.
	 */
	public void addSample(float bytesPerSecond) {
		float value = Math.max(0f, bytesPerSecond);
		float seed = displaySamples.isEmpty() ? value : displaySamples.get(displaySamples.size() - 1);
		if (targetSamples.size() >= MAX_SAMPLES) {
			targetSamples.remove(0);
			displaySamples.remove(0);
		}
		targetSamples.add(value);
		displaySamples.add(seed);
		recomputeTargetPeak();
		ensureAnimation();
	}

	private void recomputeTargetPeak() {
		float peak = 0f;
		for (int i = 0; i < targetSamples.size(); i++) {
			peak = Math.max(peak, targetSamples.get(i));
		}
		targetPeak = peak;
	}

	private void ensureAnimation() {
		if (animating) {
			return;
		}
		animating = true;
		lastFrameAtMs = android.os.SystemClock.uptimeMillis();
		postOnAnimation(frameTick);
	}

	private void stopAnimation() {
		animating = false;
		removeCallbacks(frameTick);
	}

	private void onFrame() {
		if (!animating) {
			return;
		}
		long now = android.os.SystemClock.uptimeMillis();
		float dtScale = Math.min(2.5f, Math.max(0.5f, (now - lastFrameAtMs) / (float) FRAME_MS));
		lastFrameAtMs = now;

		// Ease-out cubic shaping of the approach rate for smoother late settling.
		float ease = easeOut.getInterpolation(0.55f);
		float factor = 1f - (float) Math.pow(1f - BASE_LERP * ease, dtScale);

		boolean stillMoving = false;
		for (int i = 0; i < displaySamples.size(); i++) {
			float from = displaySamples.get(i);
			float to = targetSamples.get(i);
			float next = from + (to - from) * factor;
			if (Math.abs(to - next) < 0.5f) {
				next = to;
			} else {
				stillMoving = true;
			}
			displaySamples.set(i, next);
		}
		displayPeak = displayPeak + (targetPeak - displayPeak) * factor;
		if (Math.abs(targetPeak - displayPeak) < 0.5f) {
			displayPeak = targetPeak;
		} else {
			stillMoving = true;
		}

		invalidate();
		if (stillMoving) {
			postOnAnimation(frameTick);
		} else {
			animating = false;
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		stopAnimation();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		int width = getWidth();
		int height = getHeight();
		if (width <= 0 || height <= 0) {
			return;
		}
		float topPad = ExtraSettingsUi.dp(getContext(), 22);
		float bottom = height - ExtraSettingsUi.dp(getContext(), 4);
		float left = ExtraSettingsUi.dp(getContext(), 4);
		float right = width - ExtraSettingsUi.dp(getContext(), 4);
		canvas.drawLine(left, bottom, right, bottom, baselinePaint);

		if (displaySamples.size() < 2) {
			return;
		}
		float chartHeight = Math.max(1f, bottom - topPad);
		float max = Math.max(1f, Math.max(displayPeak, targetPeak) * 1.2f);
		float step = (right - left) / (displaySamples.size() - 1);

		fillPath.reset();
		linePath.reset();
		for (int index = 0; index < displaySamples.size(); index++) {
			float value = displaySamples.get(index);
			float x = left + index * step;
			float y = bottom - ((value / max) * chartHeight);
			if (index == 0) {
				fillPath.moveTo(x, bottom);
				fillPath.lineTo(x, y);
				linePath.moveTo(x, y);
			} else {
				// Catmull-Rom-ish simple smooth segment via midpoints for less jagged look.
				fillPath.lineTo(x, y);
				linePath.lineTo(x, y);
			}
		}
		fillPath.lineTo(right, bottom);
		fillPath.close();

		fillPaint.setShader(new LinearGradient(
			0f,
			topPad,
			0f,
			bottom,
			ColorWithAlpha(ExtraSettingsUi.COLOR_PRIMARY, 110),
			ColorWithAlpha(ExtraSettingsUi.COLOR_PRIMARY, 0),
			Shader.TileMode.CLAMP
		));
		canvas.drawPath(fillPath, fillPaint);
		canvas.drawPath(linePath, linePaint);
	}

	private static int ColorWithAlpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
	}
}
