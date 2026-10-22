package com.godot.game;

import android.util.Log;
import android.view.KeyEvent;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotRenderView;
import org.godotengine.godot.input.GodotInputHandler;

/**
 * Turns built-in keyboard presses into simulated hardware key events.
 *
 * <p>The panel only describes intent. Delivery reuses the exact path a physical
 * keyboard uses: a {@link KeyEvent} built with {@code SOURCE_KEYBOARD} and the
 * current meta state is handed to {@link GodotInputHandler#onKeyDown} /
 * {@link GodotInputHandler#onKeyUp}, which forwards it to Godot's Input thread.
 * Keys or combinations Godot chooses to ignore need no special handling.</p>
 */
final class BuiltinKeyboardDispatcher {
	private static final String TAG = "BuiltinKeyboard";

	/** Supplies the live Godot input handler, or null while the game is not ready. */
	interface InputTarget {
		GodotInputHandler inputHandler();
	}

	private BuiltinKeyboardDispatcher() {
	}

	/**
	 * Sends one key press/release pair. Returns the number of events accepted by
	 * Godot; zero means the input layer was not ready.
	 */
	static int sendKey(InputTarget target, GodotInputHandler direct, BuiltinKeyboardLayout.Key key,
			int metaState, CharSequence characters, long eventTime) {
		GodotInputHandler handler = direct != null ? direct : (target == null ? null : target.inputHandler());
		if (handler == null || key == null || key.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
			return 0;
		}
		String chars = characters == null ? "" : characters.toString();
		int accepted = 0;
		// A keyboard-shaped event: the caller's meta state travels with both the
		// press and the release, exactly like a real keyboard modifier combo.
		KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN,
			key.keyCode, 0, metaState);
		KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
		try {
			if (handler.onKeyDown(key.keyCode, down)) accepted++;
			if (handler.onKeyUp(key.keyCode, up)) accepted++;
		} catch (RuntimeException exception) {
			Log.e(TAG, "Failed to dispatch simulated key " + key, exception);
			return 0;
		}
		Log.v(TAG, "Dispatched key " + key.label
			+ " metaState=0x" + Integer.toHexString(metaState)
			+ " chars=" + describeChars(chars)
			+ " accepted=" + accepted);
		return accepted;
	}

	/** Resolves the handler once per gesture so a mid-press render restart cannot split it. */
	static GodotInputHandler resolveHandler(Godot godot) {
		if (godot == null || !godot.isInitialized()) return null;
		GodotRenderView renderView = godot.getRenderView();
		return renderView == null ? null : renderView.getInputHandler();
	}

	private static String describeChars(String chars) {
		if (chars.isEmpty()) return "none";
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < chars.length(); index++) {
			int codePoint = chars.charAt(index);
			if (codePoint < 0x20 || codePoint > 0x7e) {
				builder.append(String.format("\\u%04x", codePoint));
			} else {
				builder.append((char) codePoint);
			}
		}
		return builder.toString();
	}
}
