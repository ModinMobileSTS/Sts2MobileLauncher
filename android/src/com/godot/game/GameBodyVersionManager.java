package com.godot.game;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Backward-compatible facade for the old "active game + archive" API.
 *
 * <p>The current implementation no longer copies payloads in and out of
 * {@code files/game}.  It delegates to {@link LaunchProfileManager}: installed
 * game bodies live in {@code files/payloads/<id>/game}, and selecting a version
 * selects or creates a launch profile pointing at that payload.</p>
 */
public final class GameBodyVersionManager {
	private final LaunchProfileManager launchProfiles;

	public GameBodyVersionManager(Context context) {
		this.launchProfiles = new LaunchProfileManager(context);
	}

	public GameBodyVersion archiveActivePayload() throws Exception {
		LaunchProfileManager.GamePayload payload = launchProfiles.getSelectedPayload();
		if (payload == null || !payload.ready) {
			throw new IllegalStateException("No selected game payload to register.");
		}
		launchProfiles.createOrSelectDefaultProfileForPayload(payload, true);
		return fromPayload(payload);
	}

	public List<GameBodyVersion> listVersions() {
		List<GameBodyVersion> versions = new ArrayList<>();
		for (LaunchProfileManager.GamePayload payload : launchProfiles.listPayloads()) {
			versions.add(fromPayload(payload));
		}
		versions.sort(Comparator.comparing((GameBodyVersion version) -> version.label, String.CASE_INSENSITIVE_ORDER));
		return versions;
	}

	public GameBodyVersion getSelectedVersion() {
		LaunchProfileManager.GamePayload payload = launchProfiles.getSelectedPayload();
		return payload == null ? null : fromPayload(payload);
	}

	public String getSelectedVersionId() {
		LaunchProfileManager.GamePayload payload = launchProfiles.getSelectedPayload();
		return payload == null ? "" : payload.id;
	}

	public void selectVersion(String id) throws Exception {
		LaunchProfileManager.GamePayload payload = launchProfiles.readPayload(id);
		if (payload == null || !payload.ready) {
			throw new IllegalStateException("Game payload is missing or incomplete: " + id);
		}
		launchProfiles.createOrSelectDefaultProfileForPayload(payload, true);
	}

	public void deleteVersion(String id) throws Exception {
		if (TextUtils.isEmpty(id)) {
			return;
		}
		launchProfiles.deletePayload(id);
	}

	private GameBodyVersion fromPayload(LaunchProfileManager.GamePayload payload) {
		return new GameBodyVersion(
			payload.id,
			payload.label,
			payload.version,
			payload.commit,
			payload.gameDir,
			payload.manifest,
			payload.ready,
			payload.installedAtUnix
		);
	}

	public static final class GameBodyVersion {
		public final String id;
		public final String label;
		public final String version;
		public final String commit;
		public final File gameDir;
		public final JSONObject manifest;
		public final boolean ready;
		public final long installedAtUnix;

		GameBodyVersion(String id, String label, String version, String commit, File gameDir, JSONObject manifest, boolean ready, long installedAtUnix) {
			this.id = id;
			this.label = label;
			this.version = version;
			this.commit = commit;
			this.gameDir = gameDir;
			this.manifest = manifest;
			this.ready = ready;
			this.installedAtUnix = installedAtUnix;
		}
	}
}
