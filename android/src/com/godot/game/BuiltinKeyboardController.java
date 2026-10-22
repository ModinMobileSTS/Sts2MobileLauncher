package com.godot.game;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import org.godotengine.godot.Godot;
import org.godotengine.godot.input.GodotEditText;
import org.godotengine.godot.input.GodotInputHandler;


/**
 * UI-thread owner of the built-in full PC keyboard.
 *
 * <p>Two on-screen keyboard kinds share one floating switcher: the built-in
 * keyboard (simulated hardware key events) and the Godot system IME path. Only
 * one is visible at a time, and either tab can be chosen from the switcher that
 * floats just above whichever keyboard is showing.</p>
 */
final class BuiltinKeyboardController implements BuiltinKeyboardView.Listener,
		BuiltinKeyboardDispatcher.InputTarget {
	private static final String TAG = "BuiltinKeyboard";
	private static final long IDLE_DELAY_MS = 4000L;
	private static final float IDLE_ALPHA = 0.55f;

	private final GodotApp activity;
	private final BuiltinKeyboardState state = new BuiltinKeyboardState();
	private final Runnable idleFade = this::applyIdleFade;
	private BuiltinKeyboardView view;
	private FloatingKeyboardOverlayView floatingButton;
	private GodotEditText boundEditText;
	private boolean visible;
	private boolean deferredSystemIme;
	private boolean systemImeWasVisible;
	private volatile GodotInputHandler activeHandler;
	private boolean toggling;

	BuiltinKeyboardController(GodotApp activity) {
		this.activity = activity;
	}

	// region settings / lifecycle

	static boolean isEnabled(Activity activity) {
		return ExtraSettingsRepository.isBuiltinKeyboardEnabled(activity);
	}

	/** Re-reads the setting and synchronizes the panel with it. */
	void onResume() {
		syncFloatingButton();
		if (!isEnabled(activity)) {
			dismissAll();
			detach();
			return;
		}
		ensureView();
		// Returning to the game never reopens a keyboard without an input request.
		hideBoth(false);
	}

	void onPause() {
		dismissAll();
		detach();
		if (floatingButton != null) {
			floatingButton.detach();
			floatingButton = null;
		}
	}

	void onGodotMainLoopStarted() {
		if (!isEnabled(activity)) return;
		ensureView();
		pumpDeferredSystemIme();
	}

	/** Returns true when the built-in panel handled the Back key. */
	boolean handleBackPressed() {
		if (!visible || state.getActiveTab() != BuiltinKeyboardState.Tab.BUILTIN) return false;
		selectTab(BuiltinKeyboardState.Tab.SYSTEM);
		return true;
	}

	private void detach() {
		if (view != null) {
			view.detach();
			view = null;
		}
		boundEditText = null;
		visible = false;
		deferredSystemIme = false;
		systemImeWasVisible = false;
		activeHandler = null;
		state.setObserver(null);
		state.reset();
	}

	private void ensureView() {
		if (view != null) return;
		view = new BuiltinKeyboardView(activity, this);
		view.attachTo(activity);
		state.setObserver(this);
	}

	private void syncFloatingButton() {
		boolean enabled = false;
		try {
			enabled = new ExtraSettingsRepository(activity).loadSettingsJson()
				.optBoolean(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED, false);
		} catch (Exception exception) {
			Log.w(TAG, "Unable to read floating keyboard setting; disabling it.", exception);
		}
		if (enabled) {
			if (floatingButton == null) {
				floatingButton = new FloatingKeyboardOverlayView(activity, activity::showSoftKeyboardForGame);
			}
			floatingButton.attach();
		} else if (floatingButton != null) {
			floatingButton.detach();
			floatingButton = null;
		}
	}

	// endregion

	// region show / hide

	/** Shared request path; true means either keyboard kind is handled by this controller. */
	boolean showKeyboard() {
		if (!isEnabled(activity) || activity.getGodot() == null) {
			return GodotKeyboardBridge.showSystemKeyboard(activity.getGodot(), activity.getKeyboardHostView());
		}
		GodotEditText editText = GodotKeyboardBridge.findGodotEditText(activity.getKeyboardHostView());
		if (editText == null || !GodotKeyboardBridge.isGodotInputReady(activity.getGodot())) {
			Log.w(TAG, "Godot text-input proxy is unavailable; built-in keyboard not shown.");
			return false;
		}
		boundEditText = editText;
		return selectTab(state.getActiveTab());
	}

	/** Selects a keyboard kind without issuing a second system IME request in the caller. */
	private boolean selectTab(BuiltinKeyboardState.Tab tab) {
		if (toggling) return false;
		toggling = true;
		try {
			if (state.getActiveTab() != tab) state.reset();
			state.setActiveTab(tab);
			ensureView();
			systemImeWasVisible = false;
			if (tab == BuiltinKeyboardState.Tab.BUILTIN) {
				hideNativeIme();
				hideSystemIme();
				deferredSystemIme = false;
				visible = true;
				boundEditText = resolveEditText();
				view.showBuiltin();
				view.setAlpha(1f);
				scheduleIdleFade();
				Log.i(TAG, "Showing built-in full keyboard.");
				return true;
			}
			showSystemIme();
			return true;
		} finally {
			toggling = false;
		}
	}

	private void showSystemIme() {
		visible = true;
		deferredSystemIme = false;
		view.showSwitcherOnly();
		view.setAlpha(1f);
		if (!GodotKeyboardBridge.showSystemKeyboard(activity.getGodot(), activity.getKeyboardHostView())) {
			Log.w(TAG, "System IME request was not accepted yet; will retry when the main loop starts.");
			deferredSystemIme = true;
			return;
		}
		scheduleIdleFade();
	}

	private void pumpDeferredSystemIme() {
		if (!deferredSystemIme || view == null) return;
		if (GodotKeyboardBridge.showSystemKeyboard(activity.getGodot(), activity.getKeyboardHostView())) {
			deferredSystemIme = false;
			scheduleIdleFade();
		}
	}

	/** Hides both the keyboard and its switcher, retaining only the floating shortcut. */
	void hideBoth(boolean requestGodotHide) {
		boolean wasVisible = visible;
		visible = false;
		deferredSystemIme = false;
		systemImeWasVisible = false;
		cancelIdleFade();
		state.reset();
		if (view != null) {
			view.onHidden();
			view.setAlpha(1f);
		}
		if (wasVisible) {
			hideSystemIme();
			if (requestGodotHide) hideNativeIme();
		}
	}

	/** Removes the panel and hides any IME, e.g. when leaving the game activity. */
	void dismissAll() {
		hideBoth(true);
		boundEditText = null;
	}

	private void hideSystemIme() {
		GodotEditText editText = resolveEditText();
		if (editText != null) {
			try {
				editText.hideKeyboard();
			} catch (RuntimeException exception) {
				Log.w(TAG, "Unable to hide Godot text-input proxy keyboard.", exception);
			}
			InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
			if (imm != null) {
				imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
			}
		}
	}

	/** Tells Godot the text field is no longer being edited. */
	private void hideNativeIme() {
		Godot godot = activity.getGodot();
		if (godot == null || godot.getIo() == null) return;
		try {
			godot.getIo().hideKeyboard();
		} catch (RuntimeException exception) {
			Log.w(TAG, "Unable to notify Godot that the keyboard closed.", exception);
		}
	}

	private GodotEditText resolveEditText() {
		if (boundEditText != null) return boundEditText;
		boundEditText = GodotKeyboardBridge.findGodotEditText(activity.getKeyboardHostView());
		return boundEditText;
	}

	private void applyIdleFade() {
		if (visible && !deferredSystemIme && view != null) {
			view.animate().alpha(IDLE_ALPHA).setDuration(180).start();
		}
	}

	private void scheduleIdleFade() {
		cancelIdleFade();
		if (view == null) return;
		view.postDelayed(idleFade, IDLE_DELAY_MS);
	}

	private void cancelIdleFade() {
		if (view != null) {
			view.removeCallbacks(idleFade);
			view.animate().cancel();
		}
	}

	// endregion

	// region BuiltinKeyboardView.Listener

	@Override
	public BuiltinKeyboardState getState() {
		return state;
	}

	@Override
	public void onKeyTap(BuiltinKeyboardLayout.Key key, CharSequence text) {
		if (view == null) return;
		cancelIdleFade();
		view.setAlpha(1f);
		GodotInputHandler handler = BuiltinKeyboardDispatcher.resolveHandler(activity.getGodot());
		activeHandler = handler;
		BuiltinKeyboardDispatcher.sendKey(this, handler, key, state.metaState(), text,
			SystemClock.uptimeMillis());
	}

	@Override
	public void onLongPressModifier(BuiltinKeyboardLayout.Modifier modifier) {
		state.lockModifier(modifier);
		onStateChanged();
	}

	@Override
	public void onToggleCapsLock() {
		state.toggleCapsLock();
		onStateChanged();
	}

	@Override
	public void onStateChanged() {
		if (view != null) {
			view.refresh();
		}
	}

	@Override
	public void onSelectBuiltinTab() {
		selectTab(BuiltinKeyboardState.Tab.BUILTIN);
	}

	@Override
	public void onSelectSystemTab() {
		selectTab(BuiltinKeyboardState.Tab.SYSTEM);
	}

	@Override
	public void onCloseKeyboard() {
		hideBoth(true);
	}

	@Override
	public void onSystemImeVisibilityChanged(boolean imeVisible) {
		if (!visible || state.getActiveTab() != BuiltinKeyboardState.Tab.SYSTEM) return;
		if (imeVisible) {
			systemImeWasVisible = true;
		} else if (systemImeWasVisible) {
			// Ignore initial hidden insets while showKeyboard is still opening the IME.
			hideBoth(false);
		}
	}

	// endregion

	// region BuiltinKeyboardDispatcher.InputTarget

	@Override
	public GodotInputHandler inputHandler() {
		if (activeHandler != null) return activeHandler;
		return BuiltinKeyboardDispatcher.resolveHandler(activity.getGodot());
	}

	// endregion

	/** Diagnostics helper for the ADB automation status endpoint. */
	String describeState() {
		return "builtinKeyboardEnabled=" + isEnabled(activity)
			+ " visible=" + visible
			+ " tab=" + state.getActiveTab()
			+ " systemImmediatelyVisible=" + deferredSystemIme
			+ " shift=" + state.getState(BuiltinKeyboardLayout.Modifier.SHIFT)
			+ " ctrl=" + state.getState(BuiltinKeyboardLayout.Modifier.CTRL)
			+ " alt=" + state.getState(BuiltinKeyboardLayout.Modifier.ALT)
			+ " meta=" + state.getState(BuiltinKeyboardLayout.Modifier.META)
			+ " caps=" + state.isCapsLock();
	}
}
