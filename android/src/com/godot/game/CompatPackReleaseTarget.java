package com.godot.game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Signed matching metadata for a target in a full compatibility pack. */
public final class CompatPackReleaseTarget {
	private static final Pattern TARGET_ID = Pattern.compile("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?");

	public final String targetId;
	public final String displayName;
	public final String status;
	public final String version;
	public final List<String> versions;
	public final String channel;
	public final String steamBranch;
	public final List<String> sts2DllSha256s;

	CompatPackReleaseTarget(CompatPackReleaseVerifier.VerifiedIndex verified, int release, int entry) throws IOException {
		JSONObject json = CompatPackReleaseVerifier.arrayObject(CompatPackReleaseVerifier.requiredArray(
			CompatPackReleaseVerifier.releaseObject(verified, release), "targets"), entry);
		targetId = CompatPackReleaseVerifier.requiredText(json, "target_id");
		displayName = CompatPackReleaseVerifier.optionalText(json, "display_name");
		status = CompatPackReleaseVerifier.optionalText(json, "status");
		String primaryVersion = CompatPackReleaseVerifier.optionalText(json, "version");
		channel = CompatPackReleaseVerifier.optionalText(json, "channel");
		steamBranch = CompatPackReleaseVerifier.optionalText(json, "steam_branch");
		if (!TARGET_ID.matcher(targetId).matches() || primaryVersion.indexOf('*') >= 0) {
			throw new IOException("Invalid compatibility release target ID or version");
		}
		List<String> explicitVersions = new ArrayList<>();
		if (json.has("versions")) {
			JSONArray list = CompatPackReleaseVerifier.requiredArray(json, "versions");
			for (int i = 0; i < list.length(); i++) {
				Object value = list.opt(i);
				if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).indexOf('*') >= 0) {
					throw new IOException("Invalid compatibility release target version");
				}
				explicitVersions.add((String) value);
			}
		}
		versions = Collections.unmodifiableList(explicitVersions);
		version = primaryVersion.isEmpty() && !versions.isEmpty() ? versions.get(0) : primaryVersion;
		Object sha = json.opt("sts2_dll_sha256");
		List<String> hashes = new ArrayList<>();
		if (sha instanceof JSONArray) {
			JSONArray list = (JSONArray) sha;
			for (int i = 0; i < list.length(); i++) hashes.add(CompatPackReleaseVerifier.checkedSha256(list.opt(i)));
		} else if (sha != null) {
			hashes.add(CompatPackReleaseVerifier.checkedSha256(sha));
		}
		sts2DllSha256s = Collections.unmodifiableList(hashes);
		if (version.isEmpty() && versions.isEmpty() && sts2DllSha256s.isEmpty()) {
			throw new IOException("Compatibility release target has no explicit match identity");
		}
	}
}
