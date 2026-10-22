package com.godot.game;

import android.view.KeyEvent;

/**
 * Modifier and lock state for the built-in keyboard.
 *
 * <p>Tap a modifier to arm it for the next key only (latched); tap it again while
 * armed, or long-press it, to keep it active until tapped once more (locked).
 * Caps Lock tracks the same latched/locked cycle but persists as a letter-case
 * toggle. Plain data with no Android view dependency so it can be unit tested.</p>
 */
final class BuiltinKeyboardState {
	enum ArmState { OFF, LATCHED, LOCKED }

	private static final class Slot {
		ArmState state = ArmState.OFF;

		boolean isActive() {
			return state != ArmState.OFF;
		}
	}

	private BuiltinKeyboardView.Listener observer;

	/** Registers the panel so every state change repaints its highlighting. */
	void setObserver(BuiltinKeyboardView.Listener listener) {
		observer = listener;
	}

	private void notifyChanged() {
		if (observer != null) observer.onStateChanged();
	}

	private Tab activeTab = Tab.BUILTIN;
	private final Slot shift = new Slot();
	private final Slot ctrl = new Slot();
	private final Slot alt = new Slot();
	private final Slot meta = new Slot();
	private boolean capsLock;

	/** Single tap: OFF → LATCHED → LOCKED → OFF. */
	void tapModifier(BuiltinKeyboardLayout.Modifier modifier) {
		Slot slot = slotFor(modifier);
		if (slot == null) return;
		switch (slot.state) {
			case OFF: slot.state = ArmState.LATCHED; break;
			case LATCHED: slot.state = ArmState.LOCKED; break;
			default: slot.state = ArmState.OFF; break;
		}
		notifyChanged();
	}

	/** Long press: only OFF/LATCHED → LOCKED, never disarms a locked modifier. */
	void lockModifier(BuiltinKeyboardLayout.Modifier modifier) {
		Slot slot = slotFor(modifier);
		if (slot == null) return;
		slot.state = ArmState.LOCKED;
		notifyChanged();
	}

	ArmState getState(BuiltinKeyboardLayout.Modifier modifier) {
		Slot slot = slotFor(modifier);
		return slot == null ? ArmState.OFF : slot.state;
	}

	boolean isShiftActive() {
		return shift.isActive();
	}

	boolean isCapsLock() {
		return capsLock;
	}

	void toggleCapsLock() {
		capsLock = !capsLock;
		notifyChanged();
	}

	/** Shift is active for the next key either via Shift or while Caps Lock is on. */
	boolean isShiftLegendsActive() {
		return shift.isActive() || capsLock;
	}

	/**
	 * Drops every latched modifier after a key was typed. Locked modifiers stay.
	 *
	 * @return true when at least one modifier was released, so the caller can
	 *     refresh its highlighting.
	 */
	boolean onKeyDispatched() {
		boolean changed = false;
		for (Slot slot : new Slot[] {shift, ctrl, alt, meta}) {
			if (slot.state == ArmState.LATCHED) {
				slot.state = ArmState.OFF;
				changed = true;
			}
		}
		return changed;
	}

	/** Which on-screen keyboard the switcher last showed. */
	Tab getActiveTab() {
		return activeTab;
	}

	void setActiveTab(Tab tab) {
		activeTab = tab == null ? Tab.BUILTIN : tab;
	}

	/** Two kinds of on-screen keyboard share one floating switcher. */
	enum Tab { BUILTIN, SYSTEM }

	/** Everything resets when the keyboard hides: hidden modifiers must not leak. */
	void reset() {
		shift.state = ArmState.OFF;
		ctrl.state = ArmState.OFF;
		alt.state = ArmState.OFF;
		meta.state = ArmState.OFF;
		capsLock = false;
	}

	/** Android meta state sent with simulated key events. */
	int metaState() {
		int result = 0;
		if (shift.isActive()) {
			result |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
		}
		if (ctrl.isActive()) {
			result |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
		}
		if (alt.isActive()) {
			result |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
		}
		if (meta.isActive()) {
			result |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
		}
		if (capsLock) {
			result |= KeyEvent.META_CAPS_LOCK_ON;
		}
		return result;
	}

	/**
	 * Text committed for a character key. Modifier combos never print text; the
	 * game receives the key event plus the meta state instead.
	 */
	String resolveText(BuiltinKeyboardLayout.Key key) {
		if (key.baseText.isEmpty()) return "";
		if (ctrl.isActive() || alt.isActive() || meta.isActive()) return "";
		return key.resolveText(shift.isActive(), capsLock);
	}

	private Slot slotFor(BuiltinKeyboardLayout.Modifier modifier) {
		if (modifier == null) return null;
		switch (modifier) {
			case SHIFT: return shift;
			case CTRL: return ctrl;
			case ALT: return alt;
			case META: return meta;
			default: return null;
		}
	}
}
