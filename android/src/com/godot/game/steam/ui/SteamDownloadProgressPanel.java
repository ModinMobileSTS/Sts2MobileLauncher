package com.godot.game.steam.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.godot.game.ExtraSettingsUi;
import com.godot.game.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * MD3 + Steam-inspired inline payload download progress panel.
 * Speed labels/chart stay smoothed; the progress bar itself is static like
 * {@code SteamOperationProgressDialog} (no shimmer / frame animation).
 */
public final class SteamDownloadProgressPanel {
	private static final long AVG_WINDOW_MS = 3000L;
	private static final long LABEL_UPDATE_MS = 600L;
	private static final long CHART_PUSH_MS = 1600L;

	private final Context context;
	private final MaterialCardView root;
	private final TextView titleView;
	private final TextView branchView;
	private final TextView phaseView;
	private final TextView speedLabelView;
	private final SteamSpeedSparklineView sparklineView;
	private final TextView peakValueView;
	private final TextView transferredValueView;
	private final TextView etaValueView;
	private final TextView progressLabelView;
	private final TextView ratioView;
	private final View progressFill;
	private final FrameLayout progressTrack;
	private final TextView messageView;
	private final MaterialButton cancelButton;
	private final ArrayDeque<RateSample> rateWindow = new ArrayDeque<>();

	private long lastBytesAtMs;
	private long lastDownloadedBytes;
	private long lastLabelUpdateAtMs;
	private long lastChartPushAtMs;
	private long peakBytesPerSecond;
	private long displayedRateBps;

	private static final class RateSample {
		final long atMs;
		final long bytes;

		RateSample(long atMs, long bytes) {
			this.atMs = atMs;
			this.bytes = bytes;
		}
	}

	public SteamDownloadProgressPanel(Context context) {
		this.context = context;
		root = ExtraSettingsUi.card(context);
		root.setCardBackgroundColor(Color.rgb(28, 36, 46));
		root.setStrokeColor(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER);
		LinearLayout content = ExtraSettingsUi.cardContent(context, root);

		LinearLayout header = ExtraSettingsUi.horizontal(context);
		header.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout titleBlock = ExtraSettingsUi.vertical(context);
		titleView = ExtraSettingsUi.label(context, R.string.steam_status_downloading_payload);
		titleBlock.addView(titleView);
		branchView = ExtraSettingsUi.caption(context, "");
		branchView.setTypeface(android.graphics.Typeface.MONOSPACE);
		LinearLayout.LayoutParams branchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		branchParams.topMargin = ExtraSettingsUi.dp(context, 2);
		titleBlock.addView(branchView, branchParams);
		header.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		phaseView = badge(context, context.getString(R.string.steam_download_phase_other));
		header.addView(phaseView);
		content.addView(header);

		LinearLayout chartShell = ExtraSettingsUi.vertical(context);
		chartShell.setPadding(ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 8));
		GradientDrawable chartBg = new GradientDrawable();
		chartBg.setColor(Color.rgb(12, 14, 18));
		chartBg.setCornerRadius(ExtraSettingsUi.dp(context, 16));
		chartBg.setStroke(ExtraSettingsUi.dp(context, 1), Color.rgb(40, 46, 58));
		chartShell.setBackground(chartBg);
		LinearLayout.LayoutParams chartShellParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		chartShellParams.topMargin = ExtraSettingsUi.dp(context, 12);
		content.addView(chartShell, chartShellParams);

		speedLabelView = ExtraSettingsUi.caption(context, context.getString(R.string.steam_download_speed_label) + ": " + context.getString(R.string.steam_em_dash));
		speedLabelView.setTextColor(ExtraSettingsUi.COLOR_PRIMARY);
		chartShell.addView(speedLabelView);
		sparklineView = new SteamSpeedSparklineView(context);
		LinearLayout.LayoutParams sparkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 100));
		sparkParams.topMargin = ExtraSettingsUi.dp(context, 4);
		chartShell.addView(sparklineView, sparkParams);

		LinearLayout stats = ExtraSettingsUi.horizontal(context);
		stats.setPadding(ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 10));
		GradientDrawable statsBg = new GradientDrawable();
		statsBg.setColor(Color.argb(90, 12, 14, 18));
		statsBg.setCornerRadius(ExtraSettingsUi.dp(context, 14));
		statsBg.setStroke(ExtraSettingsUi.dp(context, 1), Color.rgb(45, 52, 66));
		stats.setBackground(statsBg);
		LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		statsParams.topMargin = ExtraSettingsUi.dp(context, 12);
		content.addView(stats, statsParams);

		peakValueView = addStatColumn(stats, R.string.steam_download_peak_label, true);
		transferredValueView = addStatColumn(stats, R.string.steam_download_transferred_label, true);
		etaValueView = addStatColumn(stats, R.string.steam_download_eta_label, false);

		LinearLayout progressHeader = ExtraSettingsUi.horizontal(context);
		progressHeader.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams progressHeaderParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		progressHeaderParams.topMargin = ExtraSettingsUi.dp(context, 12);
		content.addView(progressHeader, progressHeaderParams);
		progressLabelView = ExtraSettingsUi.caption(context, context.getString(R.string.steam_download_progress_label, 0));
		progressLabelView.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		progressHeader.addView(progressLabelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		ratioView = ExtraSettingsUi.caption(context, context.getString(R.string.steam_em_dash));
		ratioView.setTypeface(android.graphics.Typeface.MONOSPACE);
		progressHeader.addView(ratioView);

		// Same static track/fill style as SteamOperationProgressDialog.
		progressTrack = new FrameLayout(context);
		GradientDrawable trackBg = new GradientDrawable();
		trackBg.setColor(Color.rgb(12, 14, 18));
		trackBg.setCornerRadius(ExtraSettingsUi.dp(context, 100));
		trackBg.setStroke(ExtraSettingsUi.dp(context, 1), Color.rgb(45, 52, 66));
		progressTrack.setBackground(trackBg);
		progressTrack.setPadding(ExtraSettingsUi.dp(context, 2), ExtraSettingsUi.dp(context, 2), ExtraSettingsUi.dp(context, 2), ExtraSettingsUi.dp(context, 2));
		LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 12));
		trackParams.topMargin = ExtraSettingsUi.dp(context, 6);
		content.addView(progressTrack, trackParams);

		progressFill = new View(context);
		GradientDrawable fill = new GradientDrawable();
		fill.setCornerRadius(ExtraSettingsUi.dp(context, 100));
		fill.setColors(new int[] { ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_PRIMARY });
		fill.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
		progressFill.setBackground(fill);
		progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

		messageView = ExtraSettingsUi.caption(context, "");
		messageView.setTextColor(ExtraSettingsUi.COLOR_MUTED);
		LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		messageParams.topMargin = ExtraSettingsUi.dp(context, 10);
		content.addView(messageView, messageParams);

		cancelButton = ExtraSettingsUi.outlineButton(context, R.string.steam_download_cancel, R.drawable.ic_close_24);
		LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		cancelParams.topMargin = ExtraSettingsUi.dp(context, 12);
		content.addView(cancelButton, cancelParams);
	}

	public View getView() {
		return root;
	}

	public void setCancelListener(View.OnClickListener listener) {
		cancelButton.setOnClickListener(listener);
	}

	public void setCancelEnabled(boolean enabled) {
		cancelButton.setEnabled(enabled);
	}

	public void reset(String branch) {
		lastBytesAtMs = 0L;
		lastDownloadedBytes = 0L;
		lastLabelUpdateAtMs = 0L;
		lastChartPushAtMs = 0L;
		peakBytesPerSecond = 0L;
		displayedRateBps = 0L;
		rateWindow.clear();
		sparklineView.clearSamples();
		branchView.setText(context.getString(R.string.steam_download_branch_format, branch == null ? "" : branch));
		phaseView.setText(R.string.steam_download_phase_other);
		speedLabelView.setText(context.getString(R.string.steam_download_speed_label) + ": " + context.getString(R.string.steam_em_dash));
		peakValueView.setText(context.getString(R.string.steam_em_dash));
		transferredValueView.setText(context.getString(R.string.steam_em_dash));
		etaValueView.setText(context.getString(R.string.steam_em_dash));
		progressLabelView.setText(context.getString(R.string.steam_download_progress_label, 0));
		ratioView.setText(context.getString(R.string.steam_em_dash));
		messageView.setText("");
		updateProgressFill(0f);
	}

	public void stopAnimations() {
		// Progress bar is static; sparkline manages its own animation lifecycle.
	}

	public void update(String phase, int percent, String message, long downloadedBytes, long totalBytes) {
		phaseView.setText(phaseLabel(phase));
		int safePercent = Math.max(0, Math.min(100, percent));
		progressLabelView.setText(context.getString(R.string.steam_download_progress_label, safePercent));
		messageView.setText(message == null ? "" : message);
		updateProgressFill(safePercent / 100f);

		String transferred = formatBytes(downloadedBytes);
		String total = totalBytes > 0L ? formatBytes(totalBytes) : context.getString(R.string.steam_em_dash);
		transferredValueView.setText(transferred + " / " + total);
		ratioView.setText(transferred + " / " + total);

		long now = SystemClock.elapsedRealtime();
		boolean isDownloadPhase = phase != null && "download".equalsIgnoreCase(phase);
		if (isDownloadPhase && downloadedBytes >= 0L) {
			if (lastBytesAtMs == 0L) {
				lastBytesAtMs = now;
				lastDownloadedBytes = downloadedBytes;
				rateWindow.addLast(new RateSample(now, downloadedBytes));
				return;
			}
			// Always track the byte stream; only update labels/chart on their own cadences.
			if (downloadedBytes >= lastDownloadedBytes) {
				rateWindow.addLast(new RateSample(now, downloadedBytes));
				pruneRateWindow(now);
			}
			lastBytesAtMs = now;
			lastDownloadedBytes = downloadedBytes;

			long avgBps = averageBytesPerSecond(now);
			if (avgBps > 0L) {
				peakBytesPerSecond = Math.max(peakBytesPerSecond, avgBps);
			}

			if (now - lastLabelUpdateAtMs >= LABEL_UPDATE_MS) {
				lastLabelUpdateAtMs = now;
				// Soft exponential blend so the numeric readout does not thrash.
				if (displayedRateBps <= 0L) {
					displayedRateBps = avgBps;
				} else {
					displayedRateBps = (displayedRateBps * 65L + avgBps * 35L) / 100L;
				}
				speedLabelView.setText(context.getString(R.string.steam_download_speed_label) + ": " + formatRate(displayedRateBps));
				peakValueView.setText(formatRate(peakBytesPerSecond));
				if (displayedRateBps > 0L && totalBytes > downloadedBytes) {
					long remaining = totalBytes - downloadedBytes;
					etaValueView.setText(formatDuration(remaining / displayedRateBps));
				} else if (totalBytes > 0L && downloadedBytes >= totalBytes) {
					etaValueView.setText(formatDuration(0L));
				} else {
					etaValueView.setText(context.getString(R.string.steam_em_dash));
				}
			}

			if (now - lastChartPushAtMs >= CHART_PUSH_MS) {
				lastChartPushAtMs = now;
				// Chart gets the multi-second average only — much calmer than per-chunk ticks.
				sparklineView.addSample(avgBps > 0L ? avgBps : displayedRateBps);
			}
		} else if (!isDownloadPhase) {
			speedLabelView.setText(context.getString(R.string.steam_download_speed_label) + ": " + context.getString(R.string.steam_em_dash));
			etaValueView.setText(context.getString(R.string.steam_em_dash));
		}
	}

	private void pruneRateWindow(long now) {
		while (rateWindow.size() > 1 && now - rateWindow.peekFirst().atMs > AVG_WINDOW_MS) {
			rateWindow.removeFirst();
		}
	}

	private long averageBytesPerSecond(long now) {
		pruneRateWindow(now);
		if (rateWindow.size() < 2) {
			return 0L;
		}
		RateSample first = rateWindow.peekFirst();
		RateSample last = rateWindow.peekLast();
		long dt = last.atMs - first.atMs;
		long delta = last.bytes - first.bytes;
		if (dt <= 0L || delta < 0L) {
			return 0L;
		}
		return (delta * 1000L) / dt;
	}

	private TextView addStatColumn(LinearLayout parent, int labelRes, boolean withDivider) {
		LinearLayout column = ExtraSettingsUi.vertical(context);
		column.setGravity(Gravity.CENTER_HORIZONTAL);
		TextView label = ExtraSettingsUi.caption(context, context.getString(labelRes).toUpperCase(Locale.getDefault()));
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
		label.setGravity(Gravity.CENTER);
		column.addView(label);
		TextView value = ExtraSettingsUi.text(context, context.getString(R.string.steam_em_dash), 12, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD);
		value.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
		value.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		valueParams.topMargin = ExtraSettingsUi.dp(context, 2);
		column.addView(value, valueParams);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		if (withDivider) {
			params.setMarginEnd(ExtraSettingsUi.dp(context, 4));
		}
		parent.addView(column, params);
		return value;
	}

	/** Dialog-style static fill: set width from percent, no animation. */
	private void updateProgressFill(float fraction) {
		progressTrack.post(() -> {
			int width = Math.max(0, progressTrack.getWidth() - progressTrack.getPaddingLeft() - progressTrack.getPaddingRight());
			int fillWidth = Math.round(width * Math.max(0f, Math.min(1f, fraction)));
			FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
			params.width = Math.max(fillWidth, fraction > 0f ? ExtraSettingsUi.dp(context, 8) : 0);
			params.height = ViewGroup.LayoutParams.MATCH_PARENT;
			progressFill.setLayoutParams(params);
		});
	}

	private static TextView badge(Context context, String text) {
		TextView view = ExtraSettingsUi.caption(context, text);
		view.setTextColor(ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER);
		view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		view.setPadding(ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 4), ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 4));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(Color.argb(180, 31, 79, 49));
		bg.setCornerRadius(ExtraSettingsUi.dp(context, 10));
		bg.setStroke(ExtraSettingsUi.dp(context, 1), Color.argb(120, 166, 211, 183));
		view.setBackground(bg);
		return view;
	}

	private String phaseLabel(String phase) {
		if (phase == null) {
			return context.getString(R.string.steam_download_phase_other);
		}
		switch (phase.toLowerCase(Locale.ROOT)) {
			case "connect":
				return context.getString(R.string.steam_download_phase_connect);
			case "resolve":
				return context.getString(R.string.steam_download_phase_resolve);
			case "download":
				return context.getString(R.string.steam_download_phase_download);
			case "install":
				return context.getString(R.string.steam_download_phase_install);
			default:
				return context.getString(R.string.steam_download_phase_other);
		}
	}

	private String formatRate(long bytesPerSecond) {
		if (bytesPerSecond <= 0L) {
			return context.getString(R.string.steam_em_dash);
		}
		return formatBytes(bytesPerSecond) + "/s";
	}

	private static String formatBytes(long bytes) {
		if (bytes < 0L) {
			bytes = 0L;
		}
		double value = bytes;
		String[] units = { "B", "KB", "MB", "GB", "TB" };
		int unit = 0;
		while (value >= 1024d && unit < units.length - 1) {
			value /= 1024d;
			unit++;
		}
		if (unit == 0) {
			return String.format(Locale.US, "%d %s", (long) value, units[unit]);
		}
		if (value >= 100d) {
			return String.format(Locale.US, "%.0f %s", value, units[unit]);
		}
		if (value >= 10d) {
			return String.format(Locale.US, "%.1f %s", value, units[unit]);
		}
		return String.format(Locale.US, "%.2f %s", value, units[unit]);
	}

	private String formatDuration(long seconds) {
		if (seconds < 0L) {
			return context.getString(R.string.steam_em_dash);
		}
		if (seconds < 60L) {
			return seconds + "s";
		}
		long minutes = seconds / 60L;
		long rem = seconds % 60L;
		if (minutes < 60L) {
			return minutes + "m " + rem + "s";
		}
		long hours = minutes / 60L;
		long remMin = minutes % 60L;
		return hours + "h " + remMin + "m";
	}
}
