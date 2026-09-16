package com.godot.game;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotRenderView;
import org.godotengine.godot.input.GodotInputHandler;

/** UI-thread owner of the bubble and render-view-only touch-to-mouse conversion. */
final class FloatingMouseInputController {
	enum Mode { LEFT, RIGHT_ONCE, RIGHT_LOCKED }
	private static final String TAG = "FloatingMouseInput";
	private final GodotApp activity;
	private final FloatingMouseInputState state = new FloatingMouseInputState();
	private final View.OnTouchListener touchListener = this::onRenderTouch;
	private final View.OnAttachStateChangeListener attachmentListener = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) { ensureRenderViewBound(); }
		@Override public void onViewDetachedFromWindow(View view) { releaseActiveStream(); }
	};
	private final MotionEvent.PointerProperties[] mouseProperties = {new MotionEvent.PointerProperties()};
	private final MotionEvent.PointerCoords[] mouseCoords = {new MotionEvent.PointerCoords()};
	private FloatingMouseOverlayView overlay;
	private GodotRenderView boundRenderView;
	private GodotInputHandler streamInput;
	private boolean enabled;
	private boolean mouseDown;
	private long streamDownTime;
	private float lastX;
	private float lastY;

	FloatingMouseInputController(GodotApp activity) {
		this.activity = activity;
		mouseProperties[0].id = 0;
		mouseProperties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
		mouseCoords[0].size = 1;
	}

	void onResume() {
		try {
			enabled = new ExtraSettingsRepository(activity).loadSettingsJson()
				.optBoolean(ExtraSettingsRepository.KEY_FLOATING_MOUSE_ENABLED, false);
		} catch (Exception exception) {
			Log.w(TAG, "Unable to read floating mouse setting; disabling it.", exception);
			enabled = false;
		}
		if (!enabled) {
			detach();
			return;
		}
		if (overlay == null) overlay = new FloatingMouseOverlayView(activity, this::onBubbleClicked);
		overlay.attach();
		overlay.setMode(state.getMode());
		ensureRenderViewBound();
	}

	void onPause() {
		releaseActiveStream();
		if (overlay != null) overlay.onPause();
	}

	void onWindowFocusChanged(boolean hasFocus) {
		if (!hasFocus) onPause();
		else if (enabled) ensureRenderViewBound();
	}

	void onGodotMainLoopStarted() {
		if (enabled) ensureRenderViewBound();
	}

	void detach() {
		enabled = false;
		unbindRenderView();
		state.reset();
		if (overlay != null) {
			overlay.detach();
			overlay = null;
		}
	}

	private void onBubbleClicked() {
		if (!enabled) return;
		// A used RIGHT_ONCE is completed first; only an unused armed click can lock.
		releaseActiveStream();
		overlay.setMode(state.advanceOnBubbleClick());
		Log.d(TAG, "Mouse mode: " + state.getMode());
	}

	private void ensureRenderViewBound() {
		if (!enabled) return;
		Godot godot = activity.getGodot();
		GodotRenderView renderView = godot == null ? null : godot.getRenderView();
		if (renderView == boundRenderView) return;
		unbindRenderView();
		if (renderView == null || renderView.getView() == null) return;
		boundRenderView = renderView;
		renderView.getView().setOnTouchListener(touchListener);
		renderView.getView().addOnAttachStateChangeListener(attachmentListener);
	}

	private void unbindRenderView() {
		releaseActiveStream();
		if (boundRenderView != null) {
			View view = boundRenderView.getView();
			view.setOnTouchListener(null);
			view.removeOnAttachStateChangeListener(attachmentListener);
			boundRenderView = null;
		}
	}

	private boolean onRenderTouch(View view, MotionEvent event) {
		if (!enabled || boundRenderView == null || view != boundRenderView.getView()
			|| !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || event.getPointerCount() == 0) return false;
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			releaseActiveStream();
			Godot godot = activity.getGodot();
			if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
				|| !GodotApp.isGameWindowInteractive() || godot == null || !godot.isInitialized()
				|| !view.isAttachedToWindow() || boundRenderView.getInputHandler() == null) return false;
		} else if (!state.isConsuming() && event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) {
			return false;
		}
		int changingPointer = event.getPointerId(event.getActionIndex());
		FloatingMouseInputState.EventDisposition disposition = state.onFingerEvent(
			action, event.getPointerCount(), changingPointer);
		if (disposition == FloatingMouseInputState.EventDisposition.PASS_THROUGH) return false;
		if (disposition == FloatingMouseInputState.EventDisposition.SWALLOW) return true;

		int index = action == MotionEvent.ACTION_MOVE
			? event.findPointerIndex(state.getCapturedPointerId()) : event.getActionIndex();
		if (index < 0) {
			releaseActiveStream();
			return true;
		}
		lastX = event.getX(index);
		lastY = event.getY(index);
		if (action == MotionEvent.ACTION_DOWN) {
			streamDownTime = event.getDownTime();
			streamInput = boundRenderView.getInputHandler();
			mouseDown = sendMouse(MotionEvent.ACTION_DOWN, event.getEventTime());
			if (!mouseDown) {
				state.forceReleaseActiveStream();
				streamInput = null;
				completeOnce();
				Log.w(TAG, "Godot did not accept the right-button press.");
			}
		} else if (action == MotionEvent.ACTION_MOVE) {
			if (mouseDown) sendMouse(MotionEvent.ACTION_MOVE, event.getEventTime());
		} else {
			// Pointer-up and cancellation both balance exactly the accepted primary press.
			if (mouseDown) sendMouse(MotionEvent.ACTION_UP, event.getEventTime());
			mouseDown = false;
			streamInput = null;
			completeOnce();
		}
		return true;
	}

	private boolean sendMouse(int action, long eventTime) {
		if (streamInput == null) return false;
		mouseCoords[0].x = lastX;
		mouseCoords[0].y = lastY;
		mouseCoords[0].pressure = action == MotionEvent.ACTION_UP ? 0 : 1;
		MotionEvent synthetic = MotionEvent.obtain(streamDownTime, eventTime, action, 1,
			mouseProperties, mouseCoords, 0, action == MotionEvent.ACTION_UP ? 0 : MotionEvent.BUTTON_SECONDARY,
			1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
		try {
			// Generic delivery bypasses Android touch double-tap/long-press synthesis.
			// Godot still queues through its native input handler and applies its own transform.
			return streamInput.onGenericMotionEvent(synthetic);
		} finally {
			synthetic.recycle();
		}
	}

	private void releaseActiveStream() {
		boolean consumed = state.isConverting() || mouseDown;
		state.forceReleaseActiveStream();
		if (mouseDown) sendMouse(MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
		mouseDown = false;
		streamInput = null;
		if (consumed) completeOnce();
	}

	private void completeOnce() {
		if (state.onRightStreamCompleted() && overlay != null) overlay.setMode(state.getMode());
	}
}
