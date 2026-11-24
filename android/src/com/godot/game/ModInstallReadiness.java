package com.godot.game;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class ModInstallReadiness {
	private static final long RETRY_DELAY_MS = 250L;
	private static final ExecutorService WORKER = Executors.newCachedThreadPool(r -> new Thread(r, "sts2-mod-readiness"));

	private ModInstallReadiness() {}

	@FunctionalInterface
	interface Work {
		void run() throws Exception;
	}

	static void open(AppCompatActivity owner, Work attempt, Runnable onReady) {
		owner.runOnUiThread(() -> {
			if (unavailable(owner)) return;
			ScrollView scroll = new ScrollView(owner);
			scroll.setFillViewport(true);
			scroll.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
			SystemBarInsetsHelper.applySystemBarPadding(scroll, true, true, true, true);
			LinearLayout page = ExtraSettingsUi.vertical(owner);
			int padding = ExtraSettingsUi.dp(owner, 24);
			page.setPadding(padding, padding, padding, padding);
			page.setGravity(Gravity.CENTER);
			int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
			ProgressBar progress = new ProgressBar(owner);
			page.addView(progress, wrap, wrap);
			TextView status = ExtraSettingsUi.body(owner, "");
			status.setGravity(Gravity.CENTER);
			status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
			ExtraSettingsUi.addSmallSpacing(page, status);
			MaterialButton retry = ExtraSettingsUi.tonalButton(owner, R.string.mod_install_retry, R.drawable.ic_sync_24);
			MaterialButton back = ExtraSettingsUi.outlineButton(owner, R.string.mod_install_back, 0);
			page.addView(retry, wrap, wrap);
			page.addView(back, wrap, wrap);
			scroll.addView(page);
			owner.setContentView(scroll);

			Runnable check = () -> {
				retry.setVisibility(View.GONE);
				progress.setVisibility(View.VISIBLE);
				status.setText(R.string.mod_list_loading);
				run(owner, attempt, () -> status.setText(R.string.mod_install_waiting), onReady, error -> {
					progress.setVisibility(View.GONE);
					String detail = error.getMessage();
					if (detail == null || detail.isEmpty()) detail = error.toString();
					status.setText(owner.getString(R.string.error_operation_failed) + ": " + detail);
					retry.setVisibility(View.VISIBLE);
				});
			};
			retry.setOnClickListener(v -> { if (v.getVisibility() == View.VISIBLE) check.run(); });
			back.setOnClickListener(v -> owner.finish());
			check.run();
		});
	}

	/** Only for checks safe to repeat. Invoke the returned handle on main to cancel without finishing the owner. */
	static Runnable run(AppCompatActivity owner, Work attempt, Runnable onBusy, Runnable onReady, Consumer<Exception> onFailure) {
		Request request = new Request(owner, attempt, onBusy, onReady, onFailure);
		owner.runOnUiThread(request::start);
		return request::cancel;
	}

	private static boolean unavailable(AppCompatActivity owner) {
		return owner.isFinishing() || owner.isDestroyed() || owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED;
	}

	private static final class Request implements LifecycleEventObserver {
		private final AppCompatActivity owner;
		private final Work attempt;
		private final Runnable onBusy;
		private final Runnable onReady;
		private final Consumer<Exception> onFailure;
		private final Handler main = new Handler(Looper.getMainLooper());
		private final Runnable retry = this::submit;
		private Future<?> inFlight;
		private boolean notifiedBusy;
		private boolean resumed;
		private boolean recheckOnResume;
		private volatile boolean closed;

		Request(AppCompatActivity owner, Work attempt, Runnable onBusy, Runnable onReady, Consumer<Exception> onFailure) {
			this.owner = owner;
			this.attempt = attempt;
			this.onBusy = onBusy;
			this.onReady = onReady;
			this.onFailure = onFailure;
		}

		void start() {
			if (!active()) return;
			// addObserver delivers ON_RESUME immediately if the owner is already resumed.
			owner.getLifecycle().addObserver(this);
		}

		private boolean active() {
			if (!closed && unavailable(owner)) cancel();
			return !closed;
		}

		private void submit() {
			if (!active() || !resumed || inFlight != null) return;
			inFlight = WORKER.submit(() -> {
				if (closed || owner.isFinishing() || owner.isDestroyed()) return;
				try {
					attempt.run();
				} catch (Exception error) {
					main.post(() -> complete(error));
					return;
				}
				main.post(() -> complete(null));
			});
		}

		private void complete(Exception error) {
			if (!active()) return;
			inFlight = null;
			// The user may have changed profiles or started another install while this page was hidden.
			if (recheckOnResume) {
				recheckOnResume = false;
				submit();
				return;
			}
			if (error instanceof ModInstallStore.BusyException) {
				if (!notifiedBusy) {
					notifiedBusy = true;
					onBusy.run();
				}
				if (active()) main.postDelayed(retry, RETRY_DELAY_MS);
			} else if (error != null) {
				cancel();
				onFailure.accept(error);
			} else {
				cancel();
				onReady.run();
			}
		}

		@Override public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
			if (event == Lifecycle.Event.ON_DESTROY || owner.isFinishing()) {
				cancel();
			} else if (event == Lifecycle.Event.ON_PAUSE) {
				resumed = false;
				recheckOnResume = inFlight != null;
				main.removeCallbacks(retry);
			} else if (event == Lifecycle.Event.ON_RESUME) {
				resumed = true;
				submit();
			}
		}

		void cancel() {
			if (closed) return;
			closed = true;
			main.removeCallbacksAndMessages(null);
			// Drop queued checks, but never interrupt an in-progress recovery transaction.
			if (inFlight != null) inFlight.cancel(false);
			owner.getLifecycle().removeObserver(this);
		}
	}
}
