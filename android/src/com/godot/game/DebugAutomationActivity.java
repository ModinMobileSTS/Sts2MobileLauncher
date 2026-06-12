package com.godot.game;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public final class DebugAutomationActivity extends Activity {
	private static final String TAG = "Sts2Automation";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		String runId = DebugAutomationRunner.normalizeRunId(DebugAutomationRunner.extra(intent, "run_id"));
		if (!DebugAutomationRunner.isAuthorized(this, intent)) {
			Log.w(TAG, "Rejected adb automation activity request; missing or invalid token. run_id=" + runId);
			DebugAutomationRunner.writeRejected(this, intent, runId, "unauthorized");
			finish();
			return;
		}
		DebugAutomationRunner.enqueue(this, new Intent(intent), runId, () -> runOnUiThread(this::finish));
	}
}
