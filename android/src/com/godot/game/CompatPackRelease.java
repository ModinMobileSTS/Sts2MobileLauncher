package com.godot.game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A release whose complete install metadata came from a verified signed index. */
public final class CompatPackRelease {
	public final String tagName;
	public final String releaseName;
	public final String publishedUtc;
	public final String packId;
	public final String displayName;
	public final int manifestSchema;
	public final String compatVersion;
	public final int compatRevision;
	public final int minLauncherVersionCode;
	public final String minLauncherVersionName;
	public final String channel;
	public final String changelog;
	public final String releaseUrl;
	public final String zipAssetName;
	public final String zipDownloadUrl;
	public final long zipSizeBytes;
	public final String zipSha256;
	public final String keyId;
	public final boolean prerelease;
	public final List<CompatPackReleaseTarget> targets;

	CompatPackRelease(CompatPackReleaseVerifier.VerifiedIndex verified, int entry) throws IOException {
		JSONObject json = CompatPackReleaseVerifier.releaseObject(verified, entry);
		tagName = CompatPackReleaseVerifier.requiredText(json, "tag_name");
		releaseName = CompatPackReleaseVerifier.optionalText(json, "release_name");
		publishedUtc = CompatPackReleaseVerifier.optionalText(json, "published_utc");
		packId = CompatPackReleaseVerifier.requiredText(json, "pack_id");
		displayName = CompatPackReleaseVerifier.optionalText(json, "display_name");
		manifestSchema = CompatPackReleaseVerifier.positiveInt(json, "manifest_schema");
		compatVersion = CompatPackReleaseVerifier.requiredText(json, "compat_version");
		compatRevision = CompatPackReleaseVerifier.positiveInt(json, "compat_revision");
		minLauncherVersionCode = CompatPackReleaseVerifier.positiveInt(json, "min_launcher_version_code");
		minLauncherVersionName = CompatPackReleaseVerifier.optionalText(json, "min_launcher_version_name");
		channel = CompatPackReleaseVerifier.optionalText(json, "channel");
		changelog = CompatPackReleaseVerifier.optionalText(json, "changelog");
		releaseUrl = CompatPackReleaseVerifier.requiredText(json, "release_url");
		zipAssetName = CompatPackReleaseVerifier.requiredText(json, "zip_asset_name");
		zipDownloadUrl = CompatPackReleaseVerifier.requiredText(json, "zip_download_url");
		zipSizeBytes = CompatPackReleaseVerifier.positiveLong(json, "zip_size_bytes");
		zipSha256 = CompatPackReleaseVerifier.sha256Field(json, "zip_sha256");
		keyId = verified.keyId;
		Object signedPrerelease = json.opt("prerelease");
		if (signedPrerelease != null && !(signedPrerelease instanceof Boolean)) {
			throw new IOException("Invalid prerelease flag");
		}
		prerelease = Boolean.TRUE.equals(signedPrerelease);
		if (!CompatPackReleaseTrust.PACK_ID.equals(packId) || manifestSchema < 2
				|| zipSizeBytes > CompatPackReleaseVerifier.MAX_ZIP_BYTES
				|| !CompatPackReleaseTrust.ZIP_ASSET_NAME.equals(zipAssetName)
				|| !CompatPackReleaseTrust.isAllowedOfficialReleaseUrl(releaseUrl, tagName)
				|| !CompatPackReleaseTrust.isAllowedOfficialDownloadUrl(zipDownloadUrl, tagName)) {
			throw new IOException("Invalid official compatibility release metadata");
		}
		JSONArray entries = CompatPackReleaseVerifier.requiredArray(json, "targets");
		if (entries.length() == 0) throw new IOException("Compatibility release has no targets");
		List<CompatPackReleaseTarget> parsed = new ArrayList<>(entries.length());
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < entries.length(); i++) {
			CompatPackReleaseTarget target = new CompatPackReleaseTarget(verified, entry, i);
			if (!ids.add(target.targetId)) throw new IOException("Duplicate compatibility release target");
			parsed.add(target);
		}
		targets = Collections.unmodifiableList(parsed);
	}

	public boolean isInstallable(int launcherVersionCode) {
		return manifestSchema <= 2 && minLauncherVersionCode <= launcherVersionCode;
	}
}
