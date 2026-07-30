package com.godot.game;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotIO;
import org.godotengine.godot.GodotRenderView;
import org.godotengine.godot.input.GodotEditText;
import org.godotengine.godot.input.GodotInputHandler;

import android.text.InputFilter;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Connects launcher keyboard shortcuts to Godot's existing Android text-input proxy.
 *
 * <p>Godot creates one transparent {@link GodotEditText} beside its render view and
 * wires it to {@code GodotTextInputWrapper}. Reusing that view is important: showing
 * an IME for the render SurfaceView or an unrelated Android view provides no Godot
 * {@code InputConnection}, so committed text never reaches the game.</p>
 */
final class GodotKeyboardBridge {
	private static final String TAG = "GodotKeyboardBridge";

	private GodotKeyboardBridge() {
	}

	static boolean canShowSoftKeyboard(Godot godot, View rootView) {
		return isGodotInputReady(godot)
			&& godot.getIo() != null
			&& findGodotEditText(rootView) != null;
	}

	/**
	 * Requests the system IME through GodotIO while preserving the proxy's current
	 * editing state. Must be called on the Android UI thread.
	 */
	static boolean showSoftKeyboard(Godot godot, View rootView) {
		if (!isGodotInputReady(godot)) {
			Log.w(TAG, "Godot input is not ready; soft-keyboard request ignored.");
			return false;
		}

		GodotIO godotIo = godot.getIo();
		GodotEditText editText = findGodotEditText(rootView);
		if (godotIo == null || editText == null) {
			Log.w(TAG, "GodotEditText is unavailable; soft-keyboard request ignored.");
			return false;
		}

		try {
			CharSequence editable = editText.getText();
			String currentText = editable == null ? "" : editable.toString();
			int selectionStart = clampSelection(editText.getSelectionStart(), currentText.length());
			int selectionEnd = clampSelection(editText.getSelectionEnd(), currentText.length());

			// Godot uses cursorEnd == -1 to represent a caret without a selected range.
			// Android reports a caret as equal start/end values, so normalize it back to
			// the form expected by GodotEditText.showKeyboard().
			if (selectionStart < 0) {
				selectionEnd = -1;
			} else if (selectionEnd == selectionStart) {
				selectionEnd = -1;
			}

			GodotEditText.VirtualKeyboardType keyboardType = editText.getKeyboardType();
			if (keyboardType == null) {
				keyboardType = GodotEditText.VirtualKeyboardType.KEYBOARD_TYPE_DEFAULT;
			}
			int maxLength = resolveCurrentMaxLength(editText);

			installFunctionKeyForwarder(godot, editText);
			godotIo.showKeyboard(
				currentText,
				keyboardType.ordinal(),
				maxLength,
				selectionStart,
				selectionEnd);
			Log.i(TAG,
				"Requested Godot soft keyboard: textLength=" + currentText.length()
					+ " selectionStart=" + selectionStart
					+ " selectionEnd=" + selectionEnd
					+ " keyboardType=" + keyboardType
					+ " maxLength=" + maxLength);
			return true;
		} catch (Exception exception) {
			Log.e(TAG, "Failed to request Godot soft keyboard.", exception);
			return false;
		}
	}

	/**
	 * GodotEditText handles committed Unicode through its TextWatcher, but software
	 * keyboards may deliver F1-F12 as non-text KeyEvents. Its default software-key
	 * filter does not forward those keys, so route only that non-text range to the
	 * same GodotInputHandler used by a physical keyboard.
	 */
	private static void installFunctionKeyForwarder(Godot godot, GodotEditText editText) {
		editText.setOnKeyListener((view, keyCode, event) -> {
			if (event == null || !isFunctionKey(keyCode)) {
				return false;
			}
			return forwardFunctionKey(godot, keyCode, event);
		});
	}

	private static boolean forwardFunctionKey(Godot godot, int keyCode, KeyEvent event) {
		if (!isGodotInputReady(godot)) {
			return false;
		}
		GodotRenderView renderView = godot.getRenderView();
		GodotInputHandler inputHandler = renderView == null ? null : renderView.getInputHandler();
		if (inputHandler == null) {
			return false;
		}

		try {
			boolean handled;
			switch (event.getAction()) {
				case KeyEvent.ACTION_DOWN:
					handled = inputHandler.onKeyDown(keyCode, event);
					break;
				case KeyEvent.ACTION_UP:
					handled = inputHandler.onKeyUp(keyCode, event);
					break;
				default:
					return false;
			}
			Log.d(TAG,
				"Forwarded function key to Godot: keyCode=" + keyCode
					+ " action=" + event.getAction()
					+ " repeat=" + event.getRepeatCount()
					+ " handled=" + handled);
			return handled;
		} catch (Exception exception) {
			Log.e(TAG, "Failed to forward function key " + keyCode + " to Godot.", exception);
			return false;
		}
	}

	private static boolean isFunctionKey(int keyCode) {
		return keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12;
	}

	private static boolean isGodotInputReady(Godot godot) {
		return godot != null
			&& godot.isInitialized()
			&& godot.getRenderView() != null
			&& godot.getRenderView().getInputHandler() != null;
	}

	private static int clampSelection(int value, int textLength) {
		if (value < 0) {
			return -1;
		}
		return Math.min(value, textLength);
	}

	/** Godot interprets a non-positive max length as unlimited. */
	private static int resolveCurrentMaxLength(GodotEditText editText) {
		int maxLength = 0;
		InputFilter[] filters = editText.getFilters();
		if (filters == null) {
			return maxLength;
		}
		for (InputFilter filter : filters) {
			if (!(filter instanceof InputFilter.LengthFilter)) {
				continue;
			}
			int candidate = ((InputFilter.LengthFilter) filter).getMax();
			if (candidate > 0 && (maxLength == 0 || candidate < maxLength)) {
				maxLength = candidate;
			}
		}
		return maxLength;
	}

	private static GodotEditText findGodotEditText(View view) {
		if (view instanceof GodotEditText) {
			return (GodotEditText) view;
		}
		if (!(view instanceof ViewGroup)) {
			return null;
		}
		ViewGroup group = (ViewGroup) view;
		for (int index = 0; index < group.getChildCount(); index++) {
			GodotEditText result = findGodotEditText(group.getChildAt(index));
			if (result != null) {
				return result;
			}
		}
		return null;
	}
}
