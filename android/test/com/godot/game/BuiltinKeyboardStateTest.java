package com.godot.game;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Layout completeness and modifier state for the built-in full keyboard. */
public class BuiltinKeyboardStateTest {

	@Test
	public void everyPcKeyIsPresentExactlyOnce() {
		List<Integer> codes = new ArrayList<>();
		for (BuiltinKeyboardLayout.Key key : BuiltinKeyboardLayout.typedKeys()) {
			if (key.keyCode == KeyEvent.KEYCODE_UNKNOWN) continue;
			codes.add(key.keyCode);
		}
		Map<Integer, Integer> duplicates = new HashMap<>();
		for (int code : codes) {
			duplicates.merge(code, 1, Integer::sum);
		}
		List<Integer> repeated = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : duplicates.entrySet()) {
			if (entry.getValue() > 1) repeated.add(entry.getKey());
		}
		// The two Shift keys are modifiers (KEYCODE_UNKNOWN) and were skipped, so
		// every remaining key code must appear exactly once.
		assertTrue("duplicate key codes: " + repeated, repeated.isEmpty());
	}

	@Test
	public void printableAsciiCharactersAreAllReachable() {
		StringBuilder typed = new StringBuilder();
		for (BuiltinKeyboardLayout.Key key : BuiltinKeyboardLayout.typedKeys()) {
			if (key.baseText.equals(" ")) typed.append(' ');
			else if (!key.baseText.isEmpty()) typed.append(key.baseText);
		}
		for (char candidate = 'A'; candidate <= 'Z'; candidate++) {
			assertTrue("missing letter " + candidate, typed.toString().indexOf(candidate) >= 0);
		}
		for (char candidate = '0'; candidate <= '9'; candidate++) {
			assertTrue("missing digit " + candidate, typed.toString().indexOf(candidate) >= 0);
		}
		String symbols = "`-=[]\\;',./";
		for (char symbol : symbols.toCharArray()) {
			assertTrue("missing symbol " + symbol, typed.toString().indexOf(symbol) >= 0);
		}
	}

	@Test
	public void functionKeysCoverF1ToF12() {
		List<Integer> codes = new ArrayList<>();
		for (BuiltinKeyboardLayout.Key key : BuiltinKeyboardLayout.typedKeys()) {
			codes.add(key.keyCode);
		}
		for (int code = KeyEvent.KEYCODE_F1; code <= KeyEvent.KEYCODE_F12; code++) {
			assertTrue("missing function key " + code, codes.contains(code));
		}
		int[] navigation = {
			KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER,
			KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_INSERT,
			KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
			KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
		};
		for (int code : navigation) {
			assertTrue("missing key " + code, codes.contains(code));
		}
	}

	@Test
	public void letterCaseFollowsShiftAndCapsLock() {
		BuiltinKeyboardLayout.Key a = findLetter('A');
		assertNotNull(a);
		assertEquals("a", a.resolveText(false, false));
		assertEquals("A", a.resolveText(true, false));
		assertEquals("A", a.resolveText(false, true));
		assertEquals("a", a.resolveText(true, true));
	}

	@Test
	public void symbolKeysUseTheShiftedLegend() {
		BuiltinKeyboardLayout.Key digit = null;
		for (BuiltinKeyboardLayout.Key key : BuiltinKeyboardLayout.typedKeys()) {
			if ("1".equals(key.baseText)) digit = key;
		}
		assertNotNull(digit);
		assertEquals("1", digit.resolveText(false, false));
		assertEquals("!", digit.resolveText(true, false));
	}

	@Test
	public void modifierTapArmsOnceThenLocksThenReleases() {
		BuiltinKeyboardState state = new BuiltinKeyboardState();
		assertEquals(BuiltinKeyboardState.ArmState.OFF, state.getState(BuiltinKeyboardLayout.Modifier.CTRL));
		state.tapModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		assertEquals(BuiltinKeyboardState.ArmState.LATCHED, state.getState(BuiltinKeyboardLayout.Modifier.CTRL));
		assertTrue((state.metaState() & KeyEvent.META_CTRL_ON) != 0);
		state.tapModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		assertEquals(BuiltinKeyboardState.ArmState.LOCKED, state.getState(BuiltinKeyboardLayout.Modifier.CTRL));
		state.tapModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		assertEquals(BuiltinKeyboardState.ArmState.OFF, state.getState(BuiltinKeyboardLayout.Modifier.CTRL));
		assertEquals(0, state.metaState());
	}

	@Test
	public void latchedModifierReleasesAfterOneKeyButLockedStays() {
		BuiltinKeyboardState state = new BuiltinKeyboardState();
		state.tapModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		assertTrue(state.onKeyDispatched());
		assertFalse((state.metaState() & KeyEvent.META_CTRL_ON) != 0);

		state.tapModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		state.lockModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		assertFalse(state.onKeyDispatched());
		assertTrue((state.metaState() & KeyEvent.META_CTRL_ON) != 0);
	}

	@Test
	public void metaStateCombinesModifiers() {
		BuiltinKeyboardState state = new BuiltinKeyboardState();
		state.lockModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		state.lockModifier(BuiltinKeyboardLayout.Modifier.SHIFT);
		state.lockModifier(BuiltinKeyboardLayout.Modifier.ALT);
		int meta = state.metaState();
		assertTrue((meta & KeyEvent.META_CTRL_ON) != 0);
		assertTrue((meta & KeyEvent.META_SHIFT_ON) != 0);
		assertTrue((meta & KeyEvent.META_ALT_ON) != 0);
		assertEquals(0, meta & KeyEvent.META_META_ON);
	}

	@Test
	public void modifierCombosNeverCommitText() {
		BuiltinKeyboardLayout.Key c = findLetter('C');
		BuiltinKeyboardState state = new BuiltinKeyboardState();
		assertEquals("c", state.resolveText(c));
		state.lockModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		assertEquals("", state.resolveText(c));
	}

	@Test
	public void capsLockIsNotPartOfMetaUnlessToggled() {
		BuiltinKeyboardState state = new BuiltinKeyboardState();
		assertEquals(0, state.metaState());
		state.toggleCapsLock();
		assertTrue((state.metaState() & KeyEvent.META_CAPS_LOCK_ON) != 0);
		state.reset();
		assertFalse(state.isCapsLock());
		assertEquals(0, state.metaState());
	}

	@Test
	public void activeTabDefaultsToBuiltinAndIsRemembered() {
		BuiltinKeyboardState state = new BuiltinKeyboardState();
		assertEquals(BuiltinKeyboardState.Tab.BUILTIN, state.getActiveTab());
		state.setActiveTab(BuiltinKeyboardState.Tab.SYSTEM);
		assertEquals(BuiltinKeyboardState.Tab.SYSTEM, state.getActiveTab());
		state.reset();
		assertEquals(BuiltinKeyboardState.Tab.SYSTEM, state.getActiveTab());
	}

	@Test
	public void everyRowKeyHasAVisibleLabel() {
		for (BuiltinKeyboardLayout.Row row : BuiltinKeyboardLayout.allRows()) {
			for (BuiltinKeyboardLayout.Key key : row.keys) {
				assertFalse("empty label in row " + row.id,
					key.label == null || key.label.trim().isEmpty());
				assertTrue("non-positive weight in row " + row.id, key.weight > 0f);
			}
		}
	}

	private BuiltinKeyboardLayout.Key findLetter(char upper) {
		String wanted = String.valueOf(upper).toUpperCase(Locale.ROOT);
		for (BuiltinKeyboardLayout.Key key : BuiltinKeyboardLayout.typedKeys()) {
			if (key.baseText.equals(wanted)) return key;
		}
		return null;
	}

	@Test
	public void nonCharacterKeysProduceNoText() {
		for (BuiltinKeyboardLayout.Row row : BuiltinKeyboardLayout.allRows()) {
			for (BuiltinKeyboardLayout.Key key : row.keys) {
				if (key.kind != BuiltinKeyboardLayout.Kind.KEY) continue;
				if (key.baseText.isEmpty()) {
					assertEquals("", key.resolveText(true, true));
				}
			}
		}
	}

	@Test
	public void nullTabResetFallsBackToBuiltin() {
		BuiltinKeyboardState state = new BuiltinKeyboardState();
		state.setActiveTab(null);
		assertEquals(BuiltinKeyboardState.Tab.BUILTIN, state.getActiveTab());
	}
}
