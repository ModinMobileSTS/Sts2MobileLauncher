package com.godot.game.steam.cloud;

import android.content.Context;

import com.godot.game.LaunchProfileManager;
import com.godot.game.steam.auth.SteamAuthStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import in.dragonbra.javasteam.enums.EResult;

public final class Sts2SteamCloudSyncManager {
	public interface ProgressListener {
		void onProgress(int percent, String message);
	}

	public static final int STS2_APP_ID = 2868840;

	private final Context context;
	private final LaunchProfileManager launchProfiles;
	private final Sts2SteamCloudPathMapper mapper;

	public Sts2SteamCloudSyncManager(Context context) {
		this.context = context.getApplicationContext();
		this.launchProfiles = new LaunchProfileManager(this.context);
		this.mapper = new Sts2SteamCloudPathMapper(this.context);
	}

	public Status getStatus() {
		try {
			SteamAuthStore.AuthSnapshot auth = SteamAuthStore.readSnapshot(context);
			JSONObject manifest = readJsonQuietly(getManifestFile());
			JSONObject baseline = readJsonQuietly(getBaselineFile());
			return new Status(
				auth,
				getAccountRootDir(),
				getProfileId(),
				manifest == null ? 0 : manifest.optJSONArray("remote_entries") == null ? 0 : manifest.optJSONArray("remote_entries").length(),
				baseline != null,
				getCloudRootDir()
			);
		} catch (Exception exception) {
			return new Status(SteamAuthStore.readSnapshot(context), getAccountRootDir(), getProfileId(), 0, false, getCloudRootDir());
		}
	}

	public String refreshManifest(ProgressListener listener) throws Exception {
		report(listener, 5, "Connecting to Steam Cloud…");
		try (ConnectedCloud cloud = connect("cloud_manifest")) {
			report(listener, 35, "Listing remote files…");
			List<RemoteEntry> remoteEntries = listSupportedRemoteEntries(cloud.client);
			writeManifest(remoteEntries);
			writeDiagnostics("cloud-list.tsv", buildCloudListTsv(remoteEntries));
			SteamAuthStore.recordManifestSuccess(context);
			report(listener, 100, "Remote manifest refreshed.");
			return "Steam Cloud manifest: " + remoteEntries.size() + " file(s).";
		} catch (Exception exception) {
			SteamAuthStore.recordFailure(context, exception.getMessage() == null ? exception.toString() : exception.getMessage());
			throw exception;
		}
	}

	public String pullAll(ProgressListener listener) throws Exception {
		return pullAll(false, listener);
	}

	public String pullAll(boolean force, ProgressListener listener) throws Exception {
		report(listener, 3, "Connecting to Steam Cloud…");
		try (ConnectedCloud cloud = connect("cloud_pull")) {
			List<RemoteEntry> remoteEntries = listSupportedRemoteEntries(cloud.client);
			if (remoteEntries.isEmpty()) {
				writeManifest(remoteEntries);
				return "Steam Cloud has no supported STS2 save files.";
			}
			List<LocalEntry> localEntriesBeforePull = collectLocalEntries();
			JSONObject baseline = readJsonQuietly(getBaselineFile());
			List<RemoteEntry> downloadEntries = planDownloads(remoteEntries, localEntriesBeforePull, baseline, force);
			writeDiagnostics("pull-plan.tsv", buildPullPlanTsv(remoteEntries, downloadEntries));
			if (downloadEntries.isEmpty()) {
				writeManifest(remoteEntries);
				writeBaseline(localEntriesBeforePull, remoteEntries);
				SteamAuthStore.recordPullSuccess(context);
				String summary = "Steam Cloud pull skipped: all " + remoteEntries.size() + " supported file(s) already match local files.";
				writeDiagnostics("pull-summary.txt", summary + "\n");
				report(listener, 100, summary);
				return summary;
			}
			File backup = createLocalBackup();
			File staging = prepareStagingDir("pull");
			try {
				for (int i = 0; i < downloadEntries.size(); i++) {
					RemoteEntry entry = downloadEntries.get(i);
					report(listener, 10 + (int)((i * 70L) / Math.max(1, downloadEntries.size())), "Downloading " + (i + 1) + "/" + downloadEntries.size() + ": " + entry.localRelativePath);
					File out = mapper.resolveLocalFile(staging, entry.localRelativePath);
					ensureDirectory(out.getParentFile());
					cloud.client.downloadFile(STS2_APP_ID, entry.remotePath, out, entry.size, entry.sha1);
				}
				report(listener, 85, "Installing cloud files locally…");
				for (RemoteEntry entry : downloadEntries) {
					File source = mapper.resolveLocalFile(staging, entry.localRelativePath);
					File target = mapper.resolveLocalFile(getAccountRootDir(), entry.localRelativePath);
					copyFile(source, target);
				}
				writeManifest(remoteEntries);
				writeBaseline(collectLocalEntries(), remoteEntries);
				SteamAuthStore.recordPullSuccess(context);
				String summary = "Pulled " + downloadEntries.size() + " changed/missing Steam Cloud file(s); skipped " + (remoteEntries.size() - downloadEntries.size()) + " unchanged. Backup: " + backup.getName();
				writeDiagnostics("pull-summary.txt", summary + "\n");
				report(listener, 100, summary);
				return summary;
			} finally {
				deleteRecursively(staging);
			}
		} catch (Exception exception) {
			SteamAuthStore.recordFailure(context, exception.getMessage() == null ? exception.toString() : exception.getMessage());
			throw exception;
		}
	}

	public String pushLocalChanges(boolean force, ProgressListener listener) throws Exception {
		return pushLocalChanges(force, listener, true);
	}

	private String pushLocalChanges(boolean force, ProgressListener listener, boolean allowReconnectRetry) throws Exception {
		int uploadedFileCount = 0;
		report(listener, 3, "Connecting to Steam Cloud…");
		try (ConnectedCloud cloud = connect("cloud_push")) {
			List<RemoteEntry> remoteEntries = listSupportedRemoteEntries(cloud.client);
			List<LocalEntry> localEntries = collectLocalEntries();
			JSONObject baseline = readJsonQuietly(getBaselineFile());
			List<LocalEntry> uploadEntries = planUploads(localEntries, remoteEntries, baseline, force);
			validateNoDuplicateUploadPaths(uploadEntries);
			writeDiagnostics("push-plan.tsv", buildUploadPlanTsv(uploadEntries));
			if (uploadEntries.isEmpty()) {
				writeManifest(remoteEntries);
				writeBaseline(localEntries, remoteEntries);
				return "No local Steam Cloud changes to upload.";
			}
			List<String> remotePaths = new ArrayList<>();
			for (LocalEntry entry : uploadEntries) {
				remotePaths.add(entry.remotePath);
			}
			report(listener, 20, "Starting upload batch…");
			Sts2SteamCloudClient.UploadBatch batch = cloud.client.beginManualUploadBatch(STS2_APP_ID, remotePaths);
			boolean completeOk = false;
			try {
				for (int i = 0; i < uploadEntries.size(); i++) {
					LocalEntry entry = uploadEntries.get(i);
					report(listener, 25 + (int)((i * 60L) / Math.max(1, uploadEntries.size())), "Uploading " + (i + 1) + "/" + uploadEntries.size() + ": " + entry.localRelativePath);
					cloud.client.uploadFile(STS2_APP_ID, entry.remotePath, entry.file, batch.getBatchId());
					uploadedFileCount = i + 1;
					report(listener, 25 + (int)(((i + 1) * 60L) / Math.max(1, uploadEntries.size())), "Uploaded " + (i + 1) + "/" + uploadEntries.size() + ": " + entry.localRelativePath);
				}
				completeOk = true;
			} finally {
				cloud.client.completeUploadBatch(STS2_APP_ID, batch.getBatchId(), completeOk ? EResult.OK : EResult.Fail);
			}
			report(listener, 90, "Refreshing manifest…");
			List<RemoteEntry> refreshedRemote = listSupportedRemoteEntries(cloud.client);
			writeManifest(refreshedRemote);
			writeBaseline(collectLocalEntries(), refreshedRemote);
			SteamAuthStore.recordPushSuccess(context);
			String summary = "Uploaded " + uploadEntries.size() + " Steam Cloud file(s).";
			writeDiagnostics("push-summary.txt", summary + "\n");
			report(listener, 100, summary);
			return summary;
		} catch (Exception exception) {
			if (allowReconnectRetry && uploadedFileCount == 0 && isReconnectRetryCandidate(exception)) {
				report(listener, 12, "Steam upload connection stalled; reconnecting once…");
				try {
					writeDiagnostics("push-reconnect-retry.txt", describeExceptionChain(exception) + "\n");
				} catch (Exception ignored) {
					// Best effort diagnostics; do not block the reconnect retry.
				}
				return pushLocalChanges(force, listener, false);
			}
			SteamAuthStore.recordFailure(context, exception.getMessage() == null ? exception.toString() : exception.getMessage());
			throw exception;
		}
	}

	private ConnectedCloud connect(String operation) throws Exception {
		SteamAuthStore.SavedAuthMaterial auth = SteamAuthStore.readAuthMaterial(context);
		if (auth == null) {
			throw new IllegalStateException("Steam account is not logged in.");
		}
		Sts2SteamCloudClient client = new Sts2SteamCloudClient(context);
		boolean success = false;
		try {
			client.beginOperationDiagnostics(operation, auth.accountName, auth.guardData != null && !auth.guardData.trim().isEmpty());
			client.start();
			client.logOnWithRefreshToken(auth.accountName, auth.refreshToken);
			SteamAuthStore.recordSuccessfulConnect(context, client.getCurrentSteamId64());
			success = true;
			return new ConnectedCloud(client);
		} finally {
			if (!success) {
				client.close();
			}
		}
	}

	private List<RemoteEntry> listSupportedRemoteEntries(Sts2SteamCloudClient client) throws Exception {
		List<Sts2SteamCloudClient.RemoteFileRecord> records = client.listFiles(STS2_APP_ID);
		List<RemoteEntry> entries = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Sts2SteamCloudClient.RemoteFileRecord record : records) {
			String local = mapper.toLocalRelativePath(record.getRemotePath());
			if (local.isEmpty() || !seen.add(local.toLowerCase(Locale.ROOT))) {
				continue;
			}
			entries.add(new RemoteEntry(record.getRemotePath(), local, record.getRawFileSize(), record.getTimestampMs(), record.getMachineName(), record.getPersistState(), record.getSha1()));
		}
		entries.sort((a, b) -> a.localRelativePath.compareToIgnoreCase(b.localRelativePath));
		return entries;
	}

	private List<LocalEntry> collectLocalEntries() throws Exception {
		List<LocalEntry> entries = new ArrayList<>();
		File root = getAccountRootDir();
		collectLocalEntries(root, root, entries);
		entries.sort((a, b) -> a.localRelativePath.compareToIgnoreCase(b.localRelativePath));
		return entries;
	}

	private void collectLocalEntries(File root, File file, List<LocalEntry> entries) throws Exception {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					collectLocalEntries(root, child, entries);
				}
			}
			return;
		}
		String relative = buildRelativePath(root, file);
		String remote = mapper.toRemotePath(relative);
		if (remote.isEmpty()) {
			return;
		}
		entries.add(new LocalEntry(relative, remote, file, file.length(), file.lastModified(), sha1(file)));
	}

	private List<RemoteEntry> planDownloads(List<RemoteEntry> remoteEntries, List<LocalEntry> localEntries, JSONObject baseline, boolean force) throws Exception {
		Map<String, LocalEntry> localByLocal = new HashMap<>();
		for (LocalEntry local : localEntries) {
			localByLocal.put(local.localRelativePath.toLowerCase(Locale.ROOT), local);
		}
		Map<String, JSONObject> baselineByLocal = baselineByLocal(baseline);
		List<String> conflicts = new ArrayList<>();
		List<RemoteEntry> downloads = new ArrayList<>();
		for (RemoteEntry remote : remoteEntries) {
			String key = remote.localRelativePath.toLowerCase(Locale.ROOT);
			LocalEntry local = localByLocal.get(key);
			if (isSameCloudContent(local, remote)) {
				continue;
			}
			JSONObject base = baselineByLocal.get(key);
			boolean localChanged = local != null && (base == null || !local.sha1.equalsIgnoreCase(base.optString("local_sha1", "")));
			boolean remoteChanged;
			if (base == null) {
				remoteChanged = true;
			} else {
				remoteChanged = !remote.sha1.equalsIgnoreCase(base.optString("remote_sha1", ""));
			}
			if (!force && localChanged && remoteChanged) {
				conflicts.add(remote.localRelativePath);
				continue;
			}
			downloads.add(remote);
		}
		if (!conflicts.isEmpty()) {
			throw new CloudConflictException(conflicts);
		}
		return downloads;
	}

	private void validateNoDuplicateUploadPaths(List<LocalEntry> uploadEntries) {
		Map<String, LocalEntry> firstByRemotePath = new HashMap<>();
		List<String> duplicates = new ArrayList<>();
		for (LocalEntry entry : uploadEntries) {
			String key = normalizeRemotePathKey(entry.remotePath);
			LocalEntry previous = firstByRemotePath.putIfAbsent(key, entry);
			if (previous != null) {
				duplicates.add(previous.remotePath + " <= " + previous.localRelativePath + " and " + entry.localRelativePath);
			}
		}
		if (!duplicates.isEmpty()) {
			throw new IllegalStateException(
				"Steam Cloud upload plan contains duplicate remote path(s): "
					+ String.join(", ", duplicates.subList(0, Math.min(8, duplicates.size())))
			);
		}
	}

	private static String normalizeRemotePathKey(String remotePath) {
		return (remotePath == null ? "" : remotePath.trim().replace('\\', '/')).toLowerCase(Locale.ROOT);
	}

	private static boolean isSameCloudContent(LocalEntry local, RemoteEntry remote) {
		if (local == null || remote == null || local.sha1.isEmpty() || remote.sha1.isEmpty()) {
			return false;
		}
		if (!local.sha1.equalsIgnoreCase(remote.sha1)) {
			return false;
		}
		return remote.size < 0L || local.size == remote.size;
	}

	private static boolean isReconnectRetryCandidate(Throwable error) {
		Throwable current = error;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
			if ((message.contains("steam disconnected") && message.contains("unexpected"))
				|| message.contains("client or session is no longer active")
				|| message.contains("websocket transport has a watchdog")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static String describeExceptionChain(Throwable error) {
		StringBuilder builder = new StringBuilder();
		Throwable current = error;
		int depth = 0;
		while (current != null && depth < 8) {
			if (builder.length() > 0) {
				builder.append(" <- ");
			}
			String message = current.getMessage() == null ? "" : current.getMessage().trim();
			builder.append(current.getClass().getSimpleName());
			if (!message.isEmpty()) {
				builder.append(": ").append(message.replace('\r', ' ').replace('\n', ' '));
			}
			Throwable next = current.getCause();
			if (next == current) {
				break;
			}
			current = next;
			depth++;
		}
		return builder.toString();
	}

	private List<LocalEntry> planUploads(List<LocalEntry> localEntries, List<RemoteEntry> remoteEntries, JSONObject baseline, boolean force) throws Exception {
		Map<String, RemoteEntry> remoteByLocal = new HashMap<>();
		for (RemoteEntry entry : remoteEntries) {
			remoteByLocal.put(entry.localRelativePath.toLowerCase(Locale.ROOT), entry);
		}
		Map<String, JSONObject> baselineByLocal = baselineByLocal(baseline);
		List<String> conflicts = new ArrayList<>();
		List<LocalEntry> uploads = new ArrayList<>();
		for (LocalEntry local : localEntries) {
			String key = local.localRelativePath.toLowerCase(Locale.ROOT);
			RemoteEntry remote = remoteByLocal.get(key);
			JSONObject base = baselineByLocal.get(key);
			if (isSameCloudContent(local, remote)) {
				continue;
			}
			boolean localChanged = base == null || !local.sha1.equalsIgnoreCase(base.optString("local_sha1", ""));
			boolean remoteChanged;
			if (base == null) {
				remoteChanged = remote != null && !local.sha1.equalsIgnoreCase(remote.sha1);
			} else if (remote == null) {
				remoteChanged = !base.optString("remote_sha1", "").isEmpty();
			} else {
				remoteChanged = !remote.sha1.equalsIgnoreCase(base.optString("remote_sha1", ""));
			}
			if (!force && localChanged && remoteChanged) {
				conflicts.add(local.localRelativePath);
				continue;
			}
			LocalEntry upload = localForRemotePath(local, remote);
			if (force) {
				if (remote == null || !local.sha1.equalsIgnoreCase(remote.sha1)) {
					uploads.add(upload);
				}
			} else if (localChanged || remote == null) {
				uploads.add(upload);
			}
		}
		if (!conflicts.isEmpty()) {
			throw new CloudConflictException(conflicts);
		}
		return uploads;
	}

	private static LocalEntry localForRemotePath(LocalEntry local, RemoteEntry remote) {
		if (local == null || remote == null || remote.remotePath.isEmpty() || remote.remotePath.equals(local.remotePath)) {
			return local;
		}
		return new LocalEntry(local.localRelativePath, remote.remotePath, local.file, local.size, local.lastModifiedMs, local.sha1);
	}

	private Map<String, JSONObject> baselineByLocal(JSONObject baseline) {
		Map<String, JSONObject> map = new HashMap<>();
		if (baseline == null) {
			return map;
		}
		JSONArray entries = baseline.optJSONArray("entries");
		if (entries == null) {
			return map;
		}
		for (int i = 0; i < entries.length(); i++) {
			JSONObject entry = entries.optJSONObject(i);
			if (entry == null) {
				continue;
			}
			String path = entry.optString("local_relative_path", "").toLowerCase(Locale.ROOT);
			if (!path.isEmpty()) {
				map.put(path, entry);
			}
		}
		return map;
	}

	private void writeManifest(List<RemoteEntry> remoteEntries) throws Exception {
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		root.put("app_id", STS2_APP_ID);
		root.put("profile_id", getProfileId());
		root.put("account_root", getAccountRootDir().getAbsolutePath());
		root.put("fetched_at_ms", System.currentTimeMillis());
		JSONArray array = new JSONArray();
		for (RemoteEntry entry : remoteEntries) {
			array.put(entry.toJson());
		}
		root.put("remote_entries", array);
		writeText(getManifestFile(), root.toString(2));
	}

	private void writeBaseline(List<LocalEntry> localEntries, List<RemoteEntry> remoteEntries) throws Exception {
		Map<String, RemoteEntry> remoteByLocal = new HashMap<>();
		for (RemoteEntry remote : remoteEntries) {
			remoteByLocal.put(remote.localRelativePath.toLowerCase(Locale.ROOT), remote);
		}
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		root.put("app_id", STS2_APP_ID);
		root.put("profile_id", getProfileId());
		root.put("account_root", getAccountRootDir().getAbsolutePath());
		root.put("synced_at_ms", System.currentTimeMillis());
		JSONArray entries = new JSONArray();
		for (LocalEntry local : localEntries) {
			RemoteEntry remote = remoteByLocal.get(local.localRelativePath.toLowerCase(Locale.ROOT));
			JSONObject json = local.toJson();
			json.put("remote_path", remote == null ? local.remotePath : remote.remotePath);
			json.put("remote_sha1", remote == null ? "" : remote.sha1);
			json.put("remote_timestamp_ms", remote == null ? 0L : remote.timestampMs);
			entries.put(json);
		}
		root.put("entries", entries);
		writeText(getBaselineFile(), root.toString(2));
	}

	private File createLocalBackup() throws Exception {
		File backupDir = new File(getCloudRootDir(), "backups");
		ensureDirectory(backupDir);
		String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
		File zip = new File(backupDir, "sts2-steam-cloud-pull-backup-" + timestamp + ".zip");
		File root = getAccountRootDir();
		try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
			zipRecursively(root, root, output);
		}
		return zip;
	}

	private void zipRecursively(File root, File file, ZipOutputStream output) throws Exception {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					zipRecursively(root, child, output);
				}
			}
			return;
		}
		String relative = buildRelativePath(root, file);
		if (relative.isEmpty()) {
			return;
		}
		output.putNextEntry(new ZipEntry(relative));
		try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
			copy(input, output);
		}
		output.closeEntry();
	}

	private File prepareStagingDir(String prefix) throws IOException {
		File dir = new File(getCloudRootDir(), prefix + "-staging-" + System.nanoTime());
		if (!dir.mkdirs() && !dir.isDirectory()) {
			throw new IOException("Unable to create Steam Cloud staging directory: " + dir.getAbsolutePath());
		}
		return dir;
	}

	private File getAccountRootDir() {
		return launchProfiles.getSelectedAccountRootDir();
	}

	private String getProfileId() {
		LaunchProfileManager.LaunchProfile profile = launchProfiles.getSelectedProfile();
		return profile == null ? "global" : profile.id;
	}

	private File getCloudRootDir() {
		File root = new File(new File(new File(context.getFilesDir(), "steam"), "cloud"), sanitizeId(getProfileId()));
		ensureDirectory(root);
		return root;
	}

	private File getManifestFile() {
		return new File(getCloudRootDir(), "manifest.json");
	}

	private File getBaselineFile() {
		return new File(getCloudRootDir(), "baseline.json");
	}

	private void writeDiagnostics(String name, String content) throws Exception {
		File dir = new File(getCloudRootDir(), "diagnostics");
		ensureDirectory(dir);
		writeText(new File(dir, name), content);
	}

	private String buildCloudListTsv(List<RemoteEntry> entries) {
		StringBuilder builder = new StringBuilder("remote_path\tsize\ttimestamp_ms\tsha1\tmachine\tlocal_path\n");
		for (RemoteEntry entry : entries) {
			builder.append(entry.remotePath).append('\t')
				.append(entry.size).append('\t')
				.append(entry.timestampMs).append('\t')
				.append(entry.sha1).append('\t')
				.append(entry.machineName).append('\t')
				.append(entry.localRelativePath).append('\n');
		}
		return builder.toString();
	}

	private String buildPullPlanTsv(List<RemoteEntry> remoteEntries, List<RemoteEntry> downloadEntries) {
		Set<String> downloadKeys = new HashSet<>();
		for (RemoteEntry entry : downloadEntries) {
			downloadKeys.add(entry.localRelativePath.toLowerCase(Locale.ROOT));
		}
		StringBuilder builder = new StringBuilder("action\tremote_path\tlocal_path\tsize\ttimestamp_ms\tsha1\n");
		for (RemoteEntry entry : remoteEntries) {
			builder.append(downloadKeys.contains(entry.localRelativePath.toLowerCase(Locale.ROOT)) ? "download" : "skip-identical")
				.append('\t').append(entry.remotePath)
				.append('\t').append(entry.localRelativePath)
				.append('\t').append(entry.size)
				.append('\t').append(entry.timestampMs)
				.append('\t').append(entry.sha1)
				.append('\n');
		}
		return builder.toString();
	}

	private String buildUploadPlanTsv(List<LocalEntry> entries) {
		StringBuilder builder = new StringBuilder("remote_path\tlocal_path\tsize\tlast_modified_ms\tsha1\n");
		for (LocalEntry entry : entries) {
			builder.append(entry.remotePath).append('\t')
				.append(entry.localRelativePath).append('\t')
				.append(entry.size).append('\t')
				.append(entry.lastModifiedMs).append('\t')
				.append(entry.sha1).append('\n');
		}
		return builder.toString();
	}

	private JSONObject readJsonQuietly(File file) {
		try {
			if (file == null || !file.isFile()) {
				return null;
			}
			return new JSONObject(readText(file));
		} catch (Exception ignored) {
			return null;
		}
	}

	private static void report(ProgressListener listener, int percent, String message) {
		if (listener != null) {
			listener.onProgress(Math.max(0, Math.min(100, percent)), message == null ? "" : message);
		}
	}

	private static String buildRelativePath(File root, File file) throws Exception {
		String rootPath = root.getCanonicalPath();
		String filePath = file.getCanonicalPath();
		if (filePath.equals(rootPath)) {
			return "";
		}
		if (filePath.startsWith(rootPath + File.separator)) {
			return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
		}
		throw new IOException("File is outside root: " + file.getAbsolutePath());
	}

	private static String sha1(File file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		return toHex(digest.digest());
	}

	private static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(String.format(Locale.US, "%02x", value & 0xff));
		}
		return builder.toString();
	}

	private static String readText(File file) throws Exception {
		try (InputStream input = new BufferedInputStream(new FileInputStream(file)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			copy(input, output);
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static void writeText(File file, String content) throws Exception {
		File parent = file.getParentFile();
		ensureDirectory(parent);
		try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
			output.write(content.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void copyFile(File source, File target) throws Exception {
		ensureDirectory(target.getParentFile());
		try (InputStream input = new BufferedInputStream(new FileInputStream(source)); OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
			copy(input, output);
		}
	}

	private static void copy(InputStream input, OutputStream output) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
	}

	private static void ensureDirectory(File dir) {
		if (dir == null || dir.isDirectory()) {
			return;
		}
		if (!dir.mkdirs() && !dir.isDirectory()) {
			throw new IllegalStateException("Unable to create directory: " + dir.getAbsolutePath());
		}
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}

	private static String sanitizeId(String value) {
		String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
		while (sanitized.startsWith("-") || sanitized.startsWith(".")) {
			sanitized = sanitized.substring(1);
		}
		while (sanitized.endsWith("-") || sanitized.endsWith(".")) {
			sanitized = sanitized.substring(0, sanitized.length() - 1);
		}
		return sanitized.isEmpty() ? "global" : sanitized;
	}

	private static final class ConnectedCloud implements AutoCloseable {
		final Sts2SteamCloudClient client;

		ConnectedCloud(Sts2SteamCloudClient client) {
			this.client = client;
		}

		@Override
		public void close() {
			client.close();
		}
	}

	private static final class RemoteEntry {
		final String remotePath;
		final String localRelativePath;
		final long size;
		final long timestampMs;
		final String machineName;
		final String persistState;
		final String sha1;

		RemoteEntry(String remotePath, String localRelativePath, long size, long timestampMs, String machineName, String persistState, String sha1) {
			this.remotePath = remotePath == null ? "" : remotePath;
			this.localRelativePath = localRelativePath == null ? "" : localRelativePath;
			this.size = size;
			this.timestampMs = timestampMs;
			this.machineName = machineName == null ? "" : machineName;
			this.persistState = persistState == null ? "" : persistState;
			this.sha1 = sha1 == null ? "" : sha1;
		}

		JSONObject toJson() throws Exception {
			return new JSONObject()
				.put("remote_path", remotePath)
				.put("local_relative_path", localRelativePath)
				.put("size", size)
				.put("timestamp_ms", timestampMs)
				.put("machine_name", machineName)
				.put("persist_state", persistState)
				.put("sha1", sha1);
		}
	}

	private static final class LocalEntry {
		final String localRelativePath;
		final String remotePath;
		final File file;
		final long size;
		final long lastModifiedMs;
		final String sha1;

		LocalEntry(String localRelativePath, String remotePath, File file, long size, long lastModifiedMs, String sha1) {
			this.localRelativePath = localRelativePath == null ? "" : localRelativePath;
			this.remotePath = remotePath == null ? "" : remotePath;
			this.file = file;
			this.size = size;
			this.lastModifiedMs = lastModifiedMs;
			this.sha1 = sha1 == null ? "" : sha1;
		}

		JSONObject toJson() throws Exception {
			return new JSONObject()
				.put("local_relative_path", localRelativePath)
				.put("remote_path", remotePath)
				.put("size", size)
				.put("last_modified_ms", lastModifiedMs)
				.put("local_sha1", sha1);
		}
	}

	public static final class CloudConflictException extends Exception {
		private final List<String> conflicts;

		CloudConflictException(List<String> conflicts) {
			super("Steam Cloud conflict(s): " + summarizeConflicts(conflicts) + ". Choose whether to keep local files or Steam Cloud files.");
			this.conflicts = new ArrayList<>(conflicts == null ? new ArrayList<>() : conflicts);
		}

		public List<String> getConflicts() {
			return new ArrayList<>(conflicts);
		}

		public int getConflictCount() {
			return conflicts.size();
		}

		public String getConflictSummary(int limit) {
			if (conflicts.isEmpty()) {
				return "";
			}
			int count = Math.max(1, Math.min(limit, conflicts.size()));
			String summary = String.join(", ", conflicts.subList(0, count));
			if (conflicts.size() > count) {
				summary += ", … +" + (conflicts.size() - count);
			}
			return summary;
		}

		private static String summarizeConflicts(List<String> conflicts) {
			if (conflicts == null || conflicts.isEmpty()) {
				return "<unknown>";
			}
			int count = Math.min(8, conflicts.size());
			String summary = String.join(", ", conflicts.subList(0, count));
			if (conflicts.size() > count) {
				summary += ", … +" + (conflicts.size() - count);
			}
			return summary;
		}
	}

	public static final class Status {
		public final SteamAuthStore.AuthSnapshot auth;
		public final File accountRoot;
		public final String profileId;
		public final int remoteFileCount;
		public final boolean hasBaseline;
		public final File cloudRoot;

		Status(SteamAuthStore.AuthSnapshot auth, File accountRoot, String profileId, int remoteFileCount, boolean hasBaseline, File cloudRoot) {
			this.auth = auth;
			this.accountRoot = accountRoot;
			this.profileId = profileId == null ? "" : profileId;
			this.remoteFileCount = remoteFileCount;
			this.hasBaseline = hasBaseline;
			this.cloudRoot = cloudRoot;
		}
	}
}
