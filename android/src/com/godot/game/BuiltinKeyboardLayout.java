package com.godot.game;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Static definition of the built-in full PC keyboard.
 *
 * <p>Rows mirror a 104-key US ANSI desktop board: function row, number row,
 * three QWERTY rows, the bottom modifier row and the navigation cluster rows on
 * the right. Pure data only (no Android views) so the layout can be validated in
 * plain JVM tests.</p>
 */
final class BuiltinKeyboardLayout {
	enum Kind {
		/** Sends a plain key code with the current modifier state. */
		KEY,
		/** Modifier; contributes bits to the meta state instead of a key event. */
		MODIFIER,
		/** Caps Lock: toggles the shift-lock state. */
		CAPS_LOCK,
		/** Built-in keyboard / system IME switcher. */
		SWITCH
	}

	enum Modifier {
		NONE,
		SHIFT,
		CTRL,
		ALT,
		META
	}

	static final class Key {
		final String label;
		final int keyCode;
		final Kind kind;
		final Modifier modifier;
		/** Base glyph committed as text; empty for non-character keys. */
		final String baseText;
		/** Glyph used while Shift (or Caps Lock for letters) is active. */
		final String shiftText;
		/** Layout weight; 1 is a standard 1u key. */
		final float weight;
		/** Secondary legend drawn above the glyph; empty when the key has none. */
		final String hint;

		private Key(String label, int keyCode, Kind kind, Modifier modifier,
				String baseText, String shiftText, float weight, String hint) {
			this.label = label;
			this.keyCode = keyCode;
			this.kind = kind;
			this.modifier = modifier;
			this.baseText = baseText;
			this.shiftText = shiftText;
			this.weight = weight;
			this.hint = hint == null ? "" : hint;
		}

		boolean isLetter() {
			return baseText.length() == 1
				&& baseText.charAt(0) >= 'A' && baseText.charAt(0) <= 'Z';
		}

		/** Text committed for the given legend state; empty for non-text keys. */
		String resolveText(boolean shiftActive, boolean capsLock) {
			if (baseText.isEmpty()) return "";
			if (isLetter()) {
				boolean upper = shiftActive ^ capsLock;
				return upper ? baseText : baseText.toLowerCase(java.util.Locale.ROOT);
			}
			return shiftActive && !shiftText.isEmpty() ? shiftText : baseText;
		}

		@Override
		public String toString() {
			return label + "(code=" + keyCode + ",kind=" + kind + ')';
		}
	}

	static final class Row {
		final String id;
		final List<Key> keys;

		Row(String id, Key... keys) {
			this.id = id;
			this.keys = Collections.unmodifiableList(Arrays.asList(keys));
		}
	}

	private static Key key(String label, int code) {
		return new Key(label, code, Kind.KEY, Modifier.NONE, "", "", 1f, null);
	}

	private static Key key(String label, int code, float weight) {
		return new Key(label, code, Kind.KEY, Modifier.NONE, "", "", weight, null);
	}

	private static Key ch(String label, int code, String base, String shift) {
		return new Key(label, code, Kind.KEY, Modifier.NONE, base, shift, 1f, shift);
	}

	private static Key mod(String label, Modifier modifier) {
		int code = KeyEvent.KEYCODE_UNKNOWN;
		switch (modifier) {
			case SHIFT: code = KeyEvent.KEYCODE_SHIFT_LEFT; break;
			case CTRL: code = KeyEvent.KEYCODE_CTRL_LEFT; break;
			case ALT: code = KeyEvent.KEYCODE_ALT_LEFT; break;
			case META: code = KeyEvent.KEYCODE_META_LEFT; break;
			default: break;
		}
		return new Key(label, code, Kind.MODIFIER, modifier, "", "", 1.4f, null);
	}

	private static final Row FUNCTION_ROW = new Row("fn",
		key("Esc", KeyEvent.KEYCODE_ESCAPE), key("F1", KeyEvent.KEYCODE_F1),
		key("F2", KeyEvent.KEYCODE_F2), key("F3", KeyEvent.KEYCODE_F3),
		key("F4", KeyEvent.KEYCODE_F4), key("F5", KeyEvent.KEYCODE_F5),
		key("F6", KeyEvent.KEYCODE_F6), key("F7", KeyEvent.KEYCODE_F7),
		key("F8", KeyEvent.KEYCODE_F8), key("F9", KeyEvent.KEYCODE_F9),
		key("F10", KeyEvent.KEYCODE_F10), key("F11", KeyEvent.KEYCODE_F11),
		key("F12", KeyEvent.KEYCODE_F12));

	private static final Row EDIT_ROW = new Row("edit",
		key("PrtSc", KeyEvent.KEYCODE_SYSRQ), key("Ins", KeyEvent.KEYCODE_INSERT),
		key("Del", KeyEvent.KEYCODE_FORWARD_DEL), key("Home", KeyEvent.KEYCODE_MOVE_HOME),
		key("End", KeyEvent.KEYCODE_MOVE_END), key("PgUp", KeyEvent.KEYCODE_PAGE_UP),
		key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN), key("Menu", KeyEvent.KEYCODE_MENU));

	private static final Row NUMBER_ROW = new Row("number",
		ch("`", KeyEvent.KEYCODE_GRAVE, "`", "~"),
		ch("1", KeyEvent.KEYCODE_1, "1", "!"),
		ch("2", KeyEvent.KEYCODE_2, "2", "@"),
		ch("3", KeyEvent.KEYCODE_3, "3", "#"),
		ch("4", KeyEvent.KEYCODE_4, "4", "$"),
		ch("5", KeyEvent.KEYCODE_5, "5", "%"),
		ch("6", KeyEvent.KEYCODE_6, "6", "^"),
		ch("7", KeyEvent.KEYCODE_7, "7", "&"),
		ch("8", KeyEvent.KEYCODE_8, "8", "*"),
		ch("9", KeyEvent.KEYCODE_9, "9", "("),
		ch("0", KeyEvent.KEYCODE_0, "0", ")"),
		ch("-", KeyEvent.KEYCODE_MINUS, "-", "_"),
		ch("=", KeyEvent.KEYCODE_EQUALS, "=", "+"),
		key("Backspace", KeyEvent.KEYCODE_DEL, 2f));

	private static final Row QWERTY_ROW = new Row("qwerty",
		key("Tab", KeyEvent.KEYCODE_TAB, 1.5f),
		ch("Q", KeyEvent.KEYCODE_Q, "Q", ""),
		ch("W", KeyEvent.KEYCODE_W, "W", ""),
		ch("E", KeyEvent.KEYCODE_E, "E", ""),
		ch("R", KeyEvent.KEYCODE_R, "R", ""),
		ch("T", KeyEvent.KEYCODE_T, "T", ""),
		ch("Y", KeyEvent.KEYCODE_Y, "Y", ""),
		ch("U", KeyEvent.KEYCODE_U, "U", ""),
		ch("I", KeyEvent.KEYCODE_I, "I", ""),
		ch("O", KeyEvent.KEYCODE_O, "O", ""),
		ch("P", KeyEvent.KEYCODE_P, "P", ""),
		ch("[", KeyEvent.KEYCODE_LEFT_BRACKET, "[", "{"),
		ch("]", KeyEvent.KEYCODE_RIGHT_BRACKET, "]", "}"),
		ch("\\", KeyEvent.KEYCODE_BACKSLASH, "\\", "|"));

	private static final Row HOME_ROW = new Row("home",
		new Key("Caps", KeyEvent.KEYCODE_CAPS_LOCK, Kind.CAPS_LOCK, Modifier.NONE, "", "", 1.75f, null),
		ch("A", KeyEvent.KEYCODE_A, "A", ""),
		ch("S", KeyEvent.KEYCODE_S, "S", ""),
		ch("D", KeyEvent.KEYCODE_D, "D", ""),
		ch("F", KeyEvent.KEYCODE_F, "F", ""),
		ch("G", KeyEvent.KEYCODE_G, "G", ""),
		ch("H", KeyEvent.KEYCODE_H, "H", ""),
		ch("J", KeyEvent.KEYCODE_J, "J", ""),
		ch("K", KeyEvent.KEYCODE_K, "K", ""),
		ch("L", KeyEvent.KEYCODE_L, "L", ""),
		ch(";", KeyEvent.KEYCODE_SEMICOLON, ";", ":"),
		ch("'", KeyEvent.KEYCODE_APOSTROPHE, "'", "\""),
		key("Enter", KeyEvent.KEYCODE_ENTER, 2.25f));

	private static final Row BOTTOM_ROW = new Row("bottom",
		mod("Shift", Modifier.SHIFT),
		ch("Z", KeyEvent.KEYCODE_Z, "Z", ""),
		ch("X", KeyEvent.KEYCODE_X, "X", ""),
		ch("C", KeyEvent.KEYCODE_C, "C", ""),
		ch("V", KeyEvent.KEYCODE_V, "V", ""),
		ch("B", KeyEvent.KEYCODE_B, "B", ""),
		ch("N", KeyEvent.KEYCODE_N, "N", ""),
		ch("M", KeyEvent.KEYCODE_M, "M", ""),
		ch(",", KeyEvent.KEYCODE_COMMA, ",", "<"),
		ch(".", KeyEvent.KEYCODE_PERIOD, ".", ">"),
		ch("/", KeyEvent.KEYCODE_SLASH, "/", "?"),
		new Key("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, Kind.MODIFIER, Modifier.SHIFT, "", "", 2.25f, null),
		key("↑", KeyEvent.KEYCODE_DPAD_UP));

	private static final Row SPACE_ROW = new Row("space",
		mod("Ctrl", Modifier.CTRL),
		mod("Alt", Modifier.ALT),
		mod("Win", Modifier.META),
		new Key("Space", KeyEvent.KEYCODE_SPACE, Kind.KEY, Modifier.NONE, " ", " ", 6f, null),
		key("←", KeyEvent.KEYCODE_DPAD_LEFT),
		key("↓", KeyEvent.KEYCODE_DPAD_DOWN),
		key("→", KeyEvent.KEYCODE_DPAD_RIGHT));

	private static final List<Row> MAIN_ROWS = Collections.unmodifiableList(Arrays.asList(
		NUMBER_ROW, QWERTY_ROW, HOME_ROW, BOTTOM_ROW, SPACE_ROW));

	private static final List<Row> ALL_ROWS = Collections.unmodifiableList(Arrays.asList(
		FUNCTION_ROW, EDIT_ROW, NUMBER_ROW, QWERTY_ROW, HOME_ROW, BOTTOM_ROW, SPACE_ROW));

	private BuiltinKeyboardLayout() {
	}

	/** Rows drawn for the bottom QWERTY block, top to bottom. */
	static List<Row> mainRows() {
		return MAIN_ROWS;
	}

	static List<Row> allRows() {
		return ALL_ROWS;
	}

	/** Every key that produces a real key event, in a stable order. */
	static List<Key> typedKeys() {
		List<Key> result = new ArrayList<>();
		for (Row row : ALL_ROWS) {
			for (Key candidate : row.keys) {
				if (candidate.kind == Kind.KEY || candidate.kind == Kind.MODIFIER) {
					result.add(candidate);
				}
			}
		}
		return result;
	}
}
