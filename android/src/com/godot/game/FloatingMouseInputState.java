package com.godot.game;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

/** Gesture ownership is latched at DOWN, never changed by a mid-gesture mode switch. */
final class FloatingMouseInputState {
	enum EventDisposition { PASS_THROUGH, CONVERT, SWALLOW }
	static final int NO_POINTER = -1;

	private FloatingMouseInputController.Mode mode = FloatingMouseInputController.Mode.LEFT;
	private int capturedPointerId = NO_POINTER;
	private boolean swallowResidue;

	FloatingMouseInputController.Mode getMode() { return mode; }
	int getCapturedPointerId() { return capturedPointerId; }
	boolean isConverting() { return capturedPointerId != NO_POINTER; }
	boolean isConsuming() { return isConverting() || swallowResidue; }

	FloatingMouseInputController.Mode advanceOnBubbleClick() {
		switch (mode) {
			case LEFT: mode = FloatingMouseInputController.Mode.RIGHT_ONCE; break;
			case RIGHT_ONCE: mode = FloatingMouseInputController.Mode.RIGHT_LOCKED; break;
			case RIGHT_LOCKED: mode = FloatingMouseInputController.Mode.LEFT; break;
		}
		return mode;
	}

	EventDisposition onFingerEvent(int action, int pointerCount, int eventPointerId) {
		switch (action) {
			case ACTION_DOWN:
				// DOWN is a new stream, even if focus loss prevented the previous final UP.
				swallowResidue = false;
				capturedPointerId = mode == FloatingMouseInputController.Mode.LEFT ? NO_POINTER : eventPointerId;
				return isConverting() ? EventDisposition.CONVERT : EventDisposition.PASS_THROUGH;
			case ACTION_POINTER_DOWN:
				return isConsuming() ? EventDisposition.SWALLOW : EventDisposition.PASS_THROUGH;
			case ACTION_MOVE:
				return isConverting() ? EventDisposition.CONVERT
					: swallowResidue ? EventDisposition.SWALLOW : EventDisposition.PASS_THROUGH;
			case ACTION_POINTER_UP:
				if (isConverting() && eventPointerId == capturedPointerId) {
					capturedPointerId = NO_POINTER;
					swallowResidue = pointerCount > 1;
					return EventDisposition.CONVERT;
				}
				return isConsuming() ? EventDisposition.SWALLOW : EventDisposition.PASS_THROUGH;
			case ACTION_UP:
			case ACTION_CANCEL:
				EventDisposition result = isConverting() ? EventDisposition.CONVERT
					: swallowResidue ? EventDisposition.SWALLOW : EventDisposition.PASS_THROUGH;
				capturedPointerId = NO_POINTER;
				swallowResidue = false;
				return result;
			default:
				return isConsuming() ? EventDisposition.SWALLOW : EventDisposition.PASS_THROUGH;
		}
	}

	boolean onRightStreamCompleted() {
		if (mode != FloatingMouseInputController.Mode.RIGHT_ONCE) return false;
		mode = FloatingMouseInputController.Mode.LEFT;
		return true;
	}

	void forceReleaseActiveStream() {
		if (!isConverting()) return;
		capturedPointerId = NO_POINTER;
		// Even one remaining finger must not emit a stray touch/left release.
		swallowResidue = true;
	}

	void reset() {
		capturedPointerId = NO_POINTER;
		swallowResidue = false;
		mode = FloatingMouseInputController.Mode.LEFT;
	}
}
