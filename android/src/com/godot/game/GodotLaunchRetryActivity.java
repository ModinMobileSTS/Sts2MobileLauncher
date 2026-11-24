package com.godot.game;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** Prepares direct, restored and contended launches outside the native host. */
public final class GodotLaunchRetryActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		GameLaunchPreparationManager preparation = new GameLaunchPreparationManager(this);
		ModInstallReadiness.open(this, preparation::prepareForLaunch, () -> {
			Intent game = GodotApp.copyRetryArguments(getIntent(), GodotApp.createLaunchIntent(this, false));
			startActivity(preparation.preparedIntent(game));
			finish();
		});
	}
}
