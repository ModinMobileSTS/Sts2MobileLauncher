package com.godot.game;

import org.junit.Test;

import static android.view.MotionEvent.*;
import static com.godot.game.FloatingMouseInputState.EventDisposition.*;
import static com.godot.game.FloatingMouseInputController.Mode.*;
import static org.junit.Assert.*;

public class FloatingMouseInputTest {
	private final FloatingMouseInputState state = new FloatingMouseInputState();

	@Test public void singleRightGestureKeepsDragOwnedThenRestoresLeft() {
		assertEquals(RIGHT_ONCE, state.advanceOnBubbleClick());
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 3));
		assertEquals(CONVERT, state.onFingerEvent(ACTION_MOVE, 1, -1));
		assertEquals(RIGHT_ONCE, state.getMode());
		assertEquals(CONVERT, state.onFingerEvent(ACTION_UP, 1, 3));
		assertTrue(state.onRightStreamCompleted());
		assertEquals(LEFT, state.getMode());
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_DOWN, 1, 0));
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_UP, 1, 0));
	}

	@Test public void secondBubbleClickLocksAcrossRepeatedGesturesUntilThirdClick() {
		state.advanceOnBubbleClick();
		assertEquals(RIGHT_LOCKED, state.advanceOnBubbleClick());
		for (int i = 0; i < 2; i++) {
			assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 0));
			assertEquals(CONVERT, state.onFingerEvent(ACTION_UP, 1, 0));
			assertFalse(state.onRightStreamCompleted());
			assertEquals(RIGHT_LOCKED, state.getMode());
		}
		assertEquals(LEFT, state.advanceOnBubbleClick());
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_DOWN, 1, 0));
	}

	@Test public void armingDuringForwardedLeftGestureOnlyAffectsNextGesture() {
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_DOWN, 1, 4));
		state.advanceOnBubbleClick();
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_POINTER_DOWN, 2, 6));
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_MOVE, 2, -1));
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_POINTER_UP, 2, 6));
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_UP, 1, 4));
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 9));
	}

	@Test public void primaryPointerLiftSwallowsEveryRemainingFingerIncludingFinalUp() {
		state.advanceOnBubbleClick();
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 7));
		assertEquals(SWALLOW, state.onFingerEvent(ACTION_POINTER_DOWN, 2, 8));
		assertEquals(CONVERT, state.onFingerEvent(ACTION_MOVE, 2, -1));
		assertEquals(7, state.getCapturedPointerId());
		assertEquals(CONVERT, state.onFingerEvent(ACTION_POINTER_UP, 2, 7));
		state.onRightStreamCompleted();
		assertEquals(SWALLOW, state.onFingerEvent(ACTION_MOVE, 1, -1));
		assertEquals(SWALLOW, state.onFingerEvent(ACTION_UP, 1, 8));
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_DOWN, 1, 0));
	}

	@Test public void liftingUntrackedFingerCannotReleaseTheRightButton() {
		state.advanceOnBubbleClick();
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 7));
		assertEquals(SWALLOW, state.onFingerEvent(ACTION_POINTER_DOWN, 2, 8));
		assertEquals(SWALLOW, state.onFingerEvent(ACTION_POINTER_UP, 2, 8));
		assertTrue(state.isConverting());
		assertEquals(CONVERT, state.onFingerEvent(ACTION_UP, 1, 7));
	}

	@Test public void forcedReleaseConsumesEvenOneResidualFingerAndKeepsLock() {
		state.advanceOnBubbleClick();
		state.advanceOnBubbleClick();
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 7));
		state.forceReleaseActiveStream();
		assertFalse(state.isConverting());
		assertFalse(state.onRightStreamCompleted());
		assertEquals(RIGHT_LOCKED, state.getMode());
		assertEquals(SWALLOW, state.onFingerEvent(ACTION_MOVE, 1, -1));
		assertEquals(SWALLOW, state.onFingerEvent(ACTION_UP, 1, 7));
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 0));
	}

	@Test public void freshDownAfterFocusLossRecoversWhenOldFinalUpNeverArrived() {
		state.advanceOnBubbleClick();
		state.advanceOnBubbleClick();
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 7));
		state.forceReleaseActiveStream();
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 0));
		assertEquals(CONVERT, state.onFingerEvent(ACTION_CANCEL, 1, 0));
		assertFalse(state.onRightStreamCompleted());
		assertEquals(RIGHT_LOCKED, state.getMode());
	}

	@Test public void cancelledOnceCompletesAndDisableClearsResidue() {
		state.advanceOnBubbleClick();
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 0));
		assertEquals(CONVERT, state.onFingerEvent(ACTION_CANCEL, 1, 0));
		assertTrue(state.onRightStreamCompleted());
		assertEquals(LEFT, state.getMode());
		state.advanceOnBubbleClick();
		assertEquals(CONVERT, state.onFingerEvent(ACTION_DOWN, 1, 0));
		state.forceReleaseActiveStream();
		state.reset();
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_DOWN, 1, 0));
		assertEquals(PASS_THROUGH, state.onFingerEvent(ACTION_UP, 1, 0));
	}
}
