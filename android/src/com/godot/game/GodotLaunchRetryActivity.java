package com.godot.game;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** Waits outside the native host so a busy MOD installation need not block Activity creation. */
public final class GodotLaunchRetryActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ModInstallReadiness.open(this, new GameLaunchPreparationManager(this)::prepareForLaunch, () -> {
			Intent game = GodotApp.copyRetryArguments(getIntent(), GodotApp.createLaunchIntent(this, false));
			startActivity(game.putExtra("launch_prepared", true));
			finish();
		});
	}
}
