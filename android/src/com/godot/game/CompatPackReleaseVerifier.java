package com.godot.game;

import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Verifies the detached signature over exact UTF-8 bytes before reading the index schema. */
public final class CompatPackReleaseVerifier {
	static final int MAX_INDEX_BYTES = 512 * 1024;
	static final int MAX_SIGNATURE_BYTES = 8 * 1024;
	static final long MAX_ZIP_BYTES = 64 * 1024 * 1024L;
	private static final byte[] DOMAIN = "sts2-android-compat-index-v1\n".getBytes(StandardCharsets.UTF_8);
	private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
	private static final char[] HEX = "0123456789abcdef".toCharArray();
	private final Map<String, String> trustedPublicKeys;

	public CompatPackReleaseVerifier() {
		this(CompatPackReleaseTrust.trustedPublicKeys());
	}

	CompatPackReleaseVerifier(Map<String, String> trustedPublicKeys) {
		this.trustedPublicKeys = Collections.unmodifiableMap(new HashMap<>(trustedPublicKeys));
	}

	public static final class TrustUnconfiguredException extends IOException {
		TrustUnconfiguredException() {
			super("Official compatibility release signing keys are not configured");
		}
	}

	// Capability never exposed to callers. Models can only read data from a successfully verified snapshot.
	static final class VerifiedIndex {
		final JSONObject json;
		final String keyId;
		final String payloadSha256;

		private VerifiedIndex(JSONObject json, String keyId, String payloadSha256) {
			this.json = json;
			this.keyId = keyId;
			this.payloadSha256 = payloadSha256;
		}
	}

	void requireConfiguredTrust() throws TrustUnconfiguredException {
		if (trustedPublicKeys.isEmpty()) throw new TrustUnconfiguredException();
	}

	public CompatPackReleaseIndex verify(byte[] indexBytes, byte[] signatureBytes) throws IOException {
		requireConfiguredTrust();
		if (indexBytes == null || indexBytes.length == 0 || indexBytes.length > MAX_INDEX_BYTES
				|| signatureBytes == null || signatureBytes.length == 0 || signatureBytes.length > MAX_SIGNATURE_BYTES) {
			throw new IOException("Invalid compatibility index or signature size");
		}
		byte[] raw = indexBytes.clone();
		JSONObject signature = strictObject(signatureBytes);
		if (positiveInt(signature, "schema") != 1 || !"ed25519".equals(requiredText(signature, "algorithm"))) {
			throw new IOException("Unsupported compatibility index signature format");
		}
		String keyId = requiredText(signature, "key_id");
		String encodedKey = trustedPublicKeys.get(keyId);
		if (encodedKey == null) throw new IOException("Unknown compatibility release signing key");
		String payloadSha256 = sha256Field(signature, "payload_sha256");
		if (!sha256Hex(raw).equals(payloadSha256)) {
			throw new IOException("Compatibility index payload SHA-256 mismatch");
		}
		byte[] key = decodeBase64(encodedKey, 32);
		byte[] signed = decodeBase64(requiredText(signature, "signature_base64"), 64);
		Ed25519Signer signer = new Ed25519Signer();
		signer.init(false, new Ed25519PublicKeyParameters(key, 0));
		signer.update(DOMAIN, 0, DOMAIN.length);
		signer.update(raw, 0, raw.length);
		if (!signer.verifySignature(signed)) throw new IOException("Invalid compatibility release signature");
		return new CompatPackReleaseIndex(new VerifiedIndex(strictObject(raw), keyId, payloadSha256));
	}

	private static byte[] decodeBase64(String encoded, int size) throws IOException {
		if (encoded.length() == 4 * ((size + 2) / 3)) {
			try {
				byte[] raw = Base64.decode(encoded, Base64.NO_WRAP);
				if (raw.length == size && encoded.equals(Base64.encodeToString(raw, Base64.NO_WRAP))) return raw;
			} catch (IllegalArgumentException ignored) { }
		}
		throw new IOException("Invalid Ed25519 key or signature encoding");
	}

	static String sha256Hex(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			char[] text = new char[digest.length * 2];
			for (int i = 0; i < digest.length; i++) {
				text[2 * i] = HEX[(digest[i] & 0xff) >>> 4];
				text[2 * i + 1] = HEX[digest[i] & 0xf];
			}
			return new String(text);
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private static JSONObject strictObject(byte[] raw) throws IOException {
		String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
		try (JsonReader reader = new JsonReader(new StringReader(json))) {
			Object parsed = readJsonValue(reader, 0);
			if (!(parsed instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) {
				throw new IOException("Expected a single compatibility release JSON object");
			}
			return (JSONObject) parsed;
		} catch (JSONException | IllegalStateException | NumberFormatException e) {
			throw new IOException("Invalid compatibility release JSON", e);
		}
	}

	private static Object readJsonValue(JsonReader reader, int depth) throws IOException, JSONException {
		if (depth > 16) throw new IOException("Compatibility release JSON nesting too deep");
		switch (reader.peek()) {
			case BEGIN_OBJECT:
				JSONObject object = new JSONObject();
				reader.beginObject();
				while (reader.hasNext()) {
					String name = reader.nextName();
					if (object.has(name)) throw new IOException("Duplicate compatibility release JSON field");
					object.put(name, readJsonValue(reader, depth + 1));
				}
				reader.endObject();
				return object;
			case BEGIN_ARRAY:
				JSONArray array = new JSONArray();
				reader.beginArray();
				while (reader.hasNext()) array.put(readJsonValue(reader, depth + 1));
				reader.endArray();
				return array;
			case STRING:
				return reader.nextString();
			case NUMBER:
				String number = reader.nextString();
				try {
					return Long.valueOf(number);
				} catch (NumberFormatException ignored) {
					// Preserve the type so fractional/exponent/overflowing required integers cannot be coerced.
					return new BigDecimal(number);
				}
			case BOOLEAN:
				return reader.nextBoolean();
			case NULL:
				reader.nextNull();
				return JSONObject.NULL;
			default:
				throw new IOException("Expected a compatibility release JSON value");
		}
	}

	static String checkedSha256(Object value) throws IOException {
		if (!(value instanceof String) || !SHA256.matcher((String) value).matches()) throw new IOException("Invalid SHA-256 value");
		return ((String) value).toLowerCase(Locale.ROOT);
	}

	static String sha256Field(JSONObject json, String key) throws IOException {
		return checkedSha256(json.opt(key));
	}

	static String optionalText(JSONObject json, String key) throws IOException {
		Object value = json.opt(key);
		if (value == null) return "";
		if (!(value instanceof String)) throw new IOException("Invalid text field: " + key);
		return (String) value;
	}

	static String requiredText(JSONObject json, String key) throws IOException {
		String value = optionalText(json, key);
		if (value.trim().isEmpty()) throw new IOException("Missing text field: " + key);
		return value;
	}

	static long positiveLong(JSONObject json, String key) throws IOException {
		Object value = json.opt(key);
		if (!(value instanceof Long) || (long) value <= 0) throw new IOException("Invalid positive integer: " + key);
		return (long) value;
	}

	static int positiveInt(JSONObject json, String key) throws IOException {
		long value = positiveLong(json, key);
		if (value > Integer.MAX_VALUE) throw new IOException("Integer out of range: " + key);
		return (int) value;
	}

	static JSONArray requiredArray(JSONObject json, String key) throws IOException {
		JSONArray value = json.optJSONArray(key);
		if (value == null) throw new IOException("Missing array: " + key);
		return value;
	}

	static JSONObject arrayObject(JSONArray array, int entry) throws IOException {
		JSONObject object = array.optJSONObject(entry);
		if (object == null) throw new IOException("Expected object in compatibility release index array");
		return object;
	}

	static JSONObject releaseObject(VerifiedIndex verified, int entry) throws IOException {
		return arrayObject(requiredArray(verified.json, "releases"), entry);
	}
}
