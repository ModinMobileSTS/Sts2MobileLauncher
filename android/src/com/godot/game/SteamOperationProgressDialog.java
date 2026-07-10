package com.godot.game;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class SteamOperationProgressDialog {
	private final AlertDialog dialog;
	private final TextView messageView;
	private final TextView percentView;
	private final View progressFill;
	private final FrameLayout progressTrack;

	SteamOperationProgressDialog(Context context, CharSequence title, CharSequence initialMessage) {
		LinearLayout content = ExtraSettingsUi.vertical(context);
		int padding = ExtraSettingsUi.dp(context, 24);
		content.setPadding(padding, padding, padding, padding);

		TextView titleView = ExtraSettingsUi.text(context, title == null ? "" : title.toString(), 20, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD);
		titleView.setGravity(Gravity.CENTER);
		content.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		messageView = ExtraSettingsUi.body(context, initialMessage == null ? "" : initialMessage.toString());
		messageView.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		messageParams.topMargin = ExtraSettingsUi.dp(context, 14);
		content.addView(messageView, messageParams);

		percentView = ExtraSettingsUi.caption(context, context.getString(R.string.steam_operation_progress_percent, 0));
		percentView.setGravity(Gravity.CENTER);
		percentView.setTextColor(ExtraSettingsUi.COLOR_PRIMARY);
		LinearLayout.LayoutParams percentParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		percentParams.topMargin = ExtraSettingsUi.dp(context, 12);
		content.addView(percentView, percentParams);

		progressTrack = new FrameLayout(context);
		GradientDrawable trackBg = new GradientDrawable();
		trackBg.setColor(Color.rgb(12, 14, 18));
		trackBg.setCornerRadius(ExtraSettingsUi.dp(context, 100));
		trackBg.setStroke(ExtraSettingsUi.dp(context, 1), Color.rgb(45, 52, 66));
		progressTrack.setBackground(trackBg);
		progressTrack.setPadding(
			ExtraSettingsUi.dp(context, 2),
			ExtraSettingsUi.dp(context, 2),
			ExtraSettingsUi.dp(context, 2),
			ExtraSettingsUi.dp(context, 2)
		);
		LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 12));
		progressParams.topMargin = ExtraSettingsUi.dp(context, 10);
		content.addView(progressTrack, progressParams);

		progressFill = new View(context);
		GradientDrawable fill = new GradientDrawable();
		fill.setCornerRadius(ExtraSettingsUi.dp(context, 100));
		fill.setColors(new int[] { ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_PRIMARY });
		fill.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
		progressFill.setBackground(fill);
		progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

		TextView hintView = ExtraSettingsUi.caption(context, context.getString(R.string.steam_operation_progress_hint));
		hintView.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		hintParams.topMargin = ExtraSettingsUi.dp(context, 14);
		content.addView(hintView, hintParams);

		dialog = new MaterialAlertDialogBuilder(context)
			.setView(content)
			.create();
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
	}

	void show() {
		dialog.show();
	}

	void setProgress(int percent, String message) {
		messageView.setText(message == null ? "" : message);
		if (percent < 0) {
			percentView.setText(R.string.steam_em_dash);
			updateFill(0.15f);
			return;
		}
		int safe = Math.max(0, Math.min(100, percent));
		percentView.setText(dialog.getContext().getString(R.string.steam_operation_progress_percent, safe));
		updateFill(safe / 100f);
	}

	void dismiss() {
		if (dialog.isShowing()) {
			dialog.dismiss();
		}
	}

	private void updateFill(float fraction) {
		progressTrack.post(() -> {
			int width = Math.max(0, progressTrack.getWidth() - progressTrack.getPaddingLeft() - progressTrack.getPaddingRight());
			int fillWidth = Math.round(width * Math.max(0f, Math.min(1f, fraction)));
			FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
			params.width = Math.max(fillWidth, fraction > 0f ? ExtraSettingsUi.dp(dialog.getContext(), 8) : 0);
			params.height = ViewGroup.LayoutParams.MATCH_PARENT;
			progressFill.setLayoutParams(params);
		});
	}
}
