package com.godot.game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An immutable, verified snapshot of the official release index. */
public final class CompatPackReleaseIndex {
	public final String latestTag;
	public final String generatedUtc;
	public final List<CompatPackRelease> releases;
	final String payloadSha256;

	CompatPackReleaseIndex(CompatPackReleaseVerifier.VerifiedIndex verified) throws IOException {
		JSONObject json = verified.json;
		if (CompatPackReleaseVerifier.positiveInt(json, "schema") != 1
				|| !CompatPackReleaseTrust.REPOSITORY.equals(CompatPackReleaseVerifier.requiredText(json, "repository"))
				|| !CompatPackReleaseTrust.PACK_ID.equals(CompatPackReleaseVerifier.requiredText(json, "pack_id"))) {
			throw new IOException("Unsupported compatibility release index");
		}
		payloadSha256 = verified.payloadSha256;
		generatedUtc = CompatPackReleaseVerifier.optionalText(json, "generated_utc");
		latestTag = CompatPackReleaseVerifier.requiredText(json, "latest_tag");
		JSONArray entries = CompatPackReleaseVerifier.requiredArray(json, "releases");
		if (entries.length() == 0 || entries.length() > 15) throw new IOException("Invalid release count");
		List<CompatPackRelease> parsed = new ArrayList<>(entries.length());
		Set<String> tags = new HashSet<>();
		for (int i = 0; i < entries.length(); i++) {
			CompatPackRelease release = new CompatPackRelease(verified, i);
			if (!tags.add(release.tagName)) throw new IOException("Duplicate compatibility release tag");
			parsed.add(release);
		}
		if (!tags.contains(latestTag)) throw new IOException("Latest compatibility release tag is missing");
		// Signed revision ordering is authoritative, independent of the order in the envelope.
		parsed.sort((a, b) -> Integer.compare(b.compatRevision, a.compatRevision));
		releases = Collections.unmodifiableList(parsed);
	}
}
