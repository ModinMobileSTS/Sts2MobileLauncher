package com.godot.game;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class SteamOperationProgressDialog {
	private final AlertDialog dialog;
	private final ProgressBar progressBar;
	private final TextView messageView;

	SteamOperationProgressDialog(Context context, CharSequence title, CharSequence initialMessage) {
		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
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

		progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
		progressBar.setMax(100);
		progressBar.setProgress(0);
		progressBar.setIndeterminate(false);
		LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(context, 10));
		progressParams.topMargin = ExtraSettingsUi.dp(context, 22);
		content.addView(progressBar, progressParams);

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
		if (percent < 0) {
			progressBar.setIndeterminate(true);
		} else {
			progressBar.setIndeterminate(false);
			progressBar.setProgress(Math.max(0, Math.min(100, percent)));
		}
		messageView.setText(message == null ? "" : message);
	}

	void dismiss() {
		if (dialog.isShowing()) {
			dialog.dismiss();
		}
	}
}
