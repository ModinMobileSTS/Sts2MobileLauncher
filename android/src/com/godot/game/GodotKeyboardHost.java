package com.godot.game;

import android.util.Log;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Host plugin that lets the Android shell see Godot's system-text-input requests.
 *
 * <p>Godot only exposes soft-keyboard events to the embedder through a plugin.
 * Signal "android_text_input_requested" carries six ints: keyboard type,
 * max length, cursor start, cursor end and the request flag. Godot signals a
 * close by sending maxLength -1. The signal is only used as a
 * "Godot wants text input" latch; committed characters arrive through the
 * separate one-String "android_keyboard_char" signal, which matches the plugin
 * primitive signals already available in this Godot build.</p>
 *
 * <p>With the built-in full keyboard enabled the shell switches to its own panel
 * instead of the system IME; otherwise the signal handling does nothing.</p>
 */
public final class GodotKeyboardHost extends GodotPlugin {
	private static final String TAG = "GodotKeyboardHost";
	public static final String SINGLETON_NAME = "GodotKeyboardHost";
	/** Signal informing the shell that Godot opened or closed text input. */
	private static final String SIGNAL_TEXT_INPUT = "android_text_input_requested";
	/** Signal carrying committed characters back from Godot (one String argument). */
	private static final String SIGNAL_KEYBOARD_CHAR = "android_keyboard_char";

	public GodotKeyboardHost(Godot godot) {
		super(godot);
	}

	@Override
	public String getPluginName() {
		return SINGLETON_NAME;
	}

	/**
	 * Registers both signals. The six-int input signal tells the shell whether
	 * Godot currently wants text input at all; the String signal carries the
	 * characters and matches the 4.5 "param" signal the engine already exposes.
	 */
	@Override
	public Set<SignalInfo> getPluginSignals() {
		Set<SignalInfo> signals = new HashSet<>();
		signals.add(new SignalInfo(SIGNAL_TEXT_INPUT,
			Integer.class, Integer.class, Integer.class, Integer.class, Integer.class, Integer.class));
		signals.add(new SignalInfo(SIGNAL_KEYBOARD_CHAR, String.class));
		return Collections.unmodifiableSet(signals);
	}

	@Override
	public void onGodotMainLoopStarted() {
		postToUiThread(() -> {
			GodotApp activity = GodotApp.currentInstance();
			if (activity != null) {
				activity.onGodotMainLoopStartedForKeyboard();
			}
		});
	}

	/** Invoked synchronously by Godot on the Input thread. */
	public void onKeyboardRequested(int unusedA, int unusedB, int unusedC, int unusedD, int unusedE, int unusedF) {
		// Details are intentionally ignored: the plugin cannot pass the literal text,
		// so showing is driven by the character signal below.
	}

	/**
	 * Godot needs text input for the focused field (the literal text is not
	 * available through this signal, so the shell only mirrors {@code showKeyboard}).
	 */
	public void onKeyboardChar(String text) {
		GodotApp activity = GodotApp.currentInstance();
		if (activity == null) {
			return;
		}
		Log.d(TAG, "Godot text input is active; routing to the shell keyboard.");
		activity.onGameKeyboardRequestedFromGodot();
	}

	private void postToUiThread(Runnable action) {
		android.app.Activity host = getActivity();
		if (host != null) {
			host.runOnUiThread(action);
		}
	}
}
