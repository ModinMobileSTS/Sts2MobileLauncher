package com.godot.game;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Private, root-scoped ownership receipts and write-ahead rollback journal. */
final class ModInstallStore {
	// Closing a second channel for the same file can release the process's OS lock on Android.
	// Refuse overlapping local attempts before opening another descriptor.
	private static final Set<File> HELD_LOCK_FILES = ConcurrentHashMap.newKeySet();
	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
	private final File filesRoot;
	private final File modsRoot;
	private final File lexicalAppRoot;
	private final File trustedAppRoot;
	private final File registry;
	private final File index;
	private final File journal;
	private final String scope;

	static final class Module {
		final String id;
		final String manifestPath;
		final String version;
		final List<String> primaryPaths;

		Module(String id, String manifestPath, String version, Collection<String> primaryPaths) {
			if (id == null || id.trim().isEmpty() || id.indexOf('\u0000') >= 0) {
				throw new IllegalArgumentException("Invalid module id");
			}
			this.id = id;
			this.manifestPath = checkedPath(manifestPath, false);
			this.version = version == null ? "" : version;
			LinkedHashSet<String> paths = new LinkedHashSet<>();
			paths.add(this.manifestPath);
			if (primaryPaths != null) {
				for (String path : primaryPaths) paths.add(checkedPath(path, false));
			}
			this.primaryPaths = Collections.unmodifiableList(new ArrayList<>(paths));
		}

		String key() { return id + '\u0000' + manifestPath; }
	}

	static final class Review {
		final List<String> conflictPaths;
		final List<String> preservedPaths;
		final List<String> untrackedModIds;

		Review(Plan plan) {
			conflictPaths = immutable(plan.conflicts);
			preservedPaths = immutable(plan.preserved);
			untrackedModIds = immutable(plan.untracked);
		}
	}

	static final class BusyException extends IOException {
		BusyException(Throwable cause) { super("MOD installation is already busy", cause); }
	}

	static final class OverwriteException extends IOException {
		final List<String> paths;

		OverwriteException(Collection<String> paths) {
			super("Existing MOD files require overwrite confirmation: " + paths);
			this.paths = immutable(paths);
		}
	}

	ModInstallStore(File filesRoot, File modsRoot) throws IOException {
		// Context's trusted application root can use Android's /data/user/0 alias.
		// Normalize that boundary only; every path below it is checked without aliases.
		File lexicalFiles = filesRoot.getAbsoluteFile();
		File lexicalMods = modsRoot.getAbsoluteFile();
		String base = lexicalFiles.getPath() + File.separator;
		if (!lexicalMods.getPath().startsWith(base)) throw new IOException("MOD root is outside application files");
		scope = lexicalMods.getPath().substring(base.length()).replace(File.separatorChar, '/');
		path(scope, false);
		// Only the app boundary, not a symlink at files/ or below it, is trusted.
		lexicalAppRoot = lexicalFiles.getParentFile();
		if (lexicalAppRoot == null) throw new IOException("Invalid application files root");
		trustedAppRoot = lexicalAppRoot.getCanonicalFile();
		this.filesRoot = new File(trustedAppRoot, lexicalFiles.getName());
		this.modsRoot = new File(this.filesRoot, scope);
		checkPath(this.filesRoot);
		if (!this.filesRoot.isDirectory()) throw new IOException("Missing application files root");
		checkPath(this.modsRoot);
		registry = new File(this.filesRoot, "launcher/mod-installs/" + digest(scope.getBytes(StandardCharsets.UTF_8)));
		if (within(registry, this.modsRoot) || within(this.modsRoot, registry)) {
			throw new IOException("MOD root overlaps receipt storage");
		}
		ensureDirectory(registry);
		index = new File(registry, "index.json");
		journal = new File(registry, "transaction");
		try (Lease ignored = lock()) {
			File binding = new File(registry, "scope.json");
			if (exists(binding)) {
				validateBinding(binding);
			} else {
				if (exists(index) || exists(journal)) throw new IOException("Missing MOD receipt scope binding");
				try {
					JSONObject object = new JSONObject();
					object.put("schema", 1);
					object.put("scope", scope);
					atomicWrite(binding, object.toString().getBytes(StandardCharsets.UTF_8));
					atomicWrite(index, serialize(new State()));
				} catch (JSONException e) { throw corrupt("scope", e); }
			}
		}
	}

	private void validateBinding(File binding) throws IOException {
		JSONObject object = json(binding);
		try {
			if (object.getInt("schema") != 1 || !scope.equals(object.getString("scope"))) {
				throw new IOException("Receipt scope mismatch");
			}
		} catch (JSONException e) { throw corrupt("scope", e); }
	}

	Review review(File stagingRoot, String targetPrefix, List<Module> incoming,
		List<Module> replaced, boolean fullReplacement, boolean replaceDirectory) throws IOException {
		try (Lease ignored = lock()) {
			recoverLocked();
			return new Review(plan(stagingRoot, targetPrefix, incoming, replaced, fullReplacement, replaceDirectory, false));
		}
	}

	Transaction beginInstall(File stagingRoot, String targetPrefix, List<Module> incoming,
		List<Module> replaced, boolean fullReplacement, boolean allowPathConflicts,
		boolean replaceDirectory) throws IOException {
		Lease lease = lock();
		try {
			recoverLocked();
			Plan plan = plan(stagingRoot, targetPrefix, incoming, replaced, fullReplacement, replaceDirectory, false);
			if (!allowPathConflicts && !plan.conflicts.isEmpty()) throw new OverwriteException(plan.conflicts);
			return execute(plan, lease);
		} catch (IOException | RuntimeException e) {
			try { lease.close(); } catch (IOException close) { e.addSuppressed(close); }
			throw e;
		} finally {
			ModManifestCache.invalidatePath(modsRoot);
		}
	}

	Transaction beginRemoval(List<Module> removed, String directory) throws IOException {
		Lease lease = lock();
		try {
			recoverLocked();
			String prefix = path(directory, true);
			Plan plan = plan(null, prefix, Collections.emptyList(), removed, true, !prefix.isEmpty(), true);
			return execute(plan, lease);
		} catch (IOException | RuntimeException e) {
			try { lease.close(); } catch (IOException close) { e.addSuppressed(close); }
			throw e;
		} finally {
			ModManifestCache.invalidatePath(modsRoot);
		}
	}

	void recover() throws IOException {
		recover(null);
	}

	interface Maintenance {
		void run() throws IOException;
	}

	void recover(Maintenance afterRecovery) throws IOException {
		try (Lease ignored = lock()) {
			recoverLocked();
			readState(index);
			ensureDirectory(modsRoot);
			if (afterRecovery != null) afterRecovery.run();
		}
	}

	/** Final non-blocking launch handoff. Recovery (possibly a large copy) belongs on the worker. */
	boolean withCleanRoot(Runnable whileLocked) throws IOException {
		try (Lease ignored = lock()) {
			validateBinding(new File(registry, "scope.json"));
			if (!OsConstants.S_ISDIR(checkPath(modsRoot))) throw new IOException("Missing MOD directory: " + modsRoot);
			if (exists(journal)) {
				if (!journal.isDirectory()) throw new IOException("Invalid MOD transaction directory");
				return false;
			}
			readState(index);
			whileLocked.run();
			return true;
		}
	}

	final class Transaction implements AutoCloseable {
		final List<String> preservedPaths;
		final List<String> untrackedModIds;
		final File preservedDirectory;
		private Lease lease;
		private boolean committed;
		private boolean rolledBack;

		private Transaction(Plan plan, Lease lease, File preservedDirectory) {
			this.lease = lease;
			this.preservedPaths = immutable(plan.preserved);
			this.untrackedModIds = immutable(plan.untracked);
			this.preservedDirectory = preservedDirectory;
		}

		void commit() throws IOException {
			if (committed) return;
			if (rolledBack || lease == null) throw new IOException("Transaction is no longer active");
			// Until this atomic, synced marker succeeds, close/rollback can still restore everything.
			try {
				atomicWrite(new File(journal, "phase"), bytes("COMMITTED"));
				committed = true;
			} finally {
				ModManifestCache.invalidatePath(modsRoot);
			}
			IOException failure = null;
			try { cleanupJournal(); } catch (IOException ignored) { /* Recovery finishes committed cleanup. */ }
			try { lease.close(); } catch (IOException e) { failure = e; }
			lease = null;
			if (failure != null) throw failure;
		}

		void rollback() throws IOException {
			if (committed || rolledBack) return;
			if (lease == null) throw new IOException("Transaction lock is not held");
			IOException failure = null;
			try {
				recoverLocked();
				rolledBack = true;
			} catch (IOException e) { failure = e; }
			finally {
				ModManifestCache.invalidatePath(modsRoot);
				try { lease.close(); } catch (IOException e) {
					if (failure == null) failure = e; else failure.addSuppressed(e);
				}
				lease = null;
			}
			if (failure != null) throw failure;
		}

		@Override public void close() throws IOException {
			if (!committed && !rolledBack && lease != null) rollback();
		}
	}

	private static final class Entry {
		final long length;
		final String sha256;
		final LinkedHashSet<String> owners;

		Entry(long length, String sha256, Collection<String> owners) {
			this.length = length;
			this.sha256 = sha256;
			this.owners = new LinkedHashSet<>(owners);
		}
	}

	private static final class State {
		final LinkedHashMap<String, Module> modules = new LinkedHashMap<>();
		final LinkedHashMap<String, Entry> files = new LinkedHashMap<>();
	}

	private static final class Plan {
		State next;
		String directory;
		File staging;
		final LinkedHashMap<String, File> incoming = new LinkedHashMap<>();
		final LinkedHashSet<String> delete = new LinkedHashSet<>();
		final LinkedHashSet<String> backupModified = new LinkedHashSet<>();
		final LinkedHashSet<String> conflicts = new LinkedHashSet<>();
		final LinkedHashSet<String> preserved = new LinkedHashSet<>();
		final LinkedHashSet<String> untracked = new LinkedHashSet<>();
	}

	private Plan plan(File stagingRoot, String targetPrefix, List<Module> incomingModules,
		List<Module> replacedModules, boolean full, boolean wholeDirectory, boolean removal) throws IOException {
		String prefix = path(targetPrefix, true);
		if (wholeDirectory && prefix.isEmpty()) throw new IOException("Whole-directory replacement needs an item path");
		List<Module> incoming = incomingModules == null ? Collections.emptyList() : incomingModules;
		List<Module> replaced = replacedModules == null ? Collections.emptyList() : replacedModules;
		State old = readState(index);
		Plan plan = new Plan();
		plan.next = copyState(old);
		plan.directory = wholeDirectory ? prefix : "";
		plan.staging = stagingRoot == null ? null : normalizeStagingRoot(stagingRoot);
		LinkedHashSet<String> selected = new LinkedHashSet<>();
		LinkedHashSet<String> legacyPrimary = new LinkedHashSet<>();
		for (Module module : replaced) {
			validateModule(module);
			selected.add(module.key());
			if (!old.modules.containsKey(module.key())) {
				plan.untracked.add(module.id);
				boolean recordedId = false;
				for (Module recorded : old.modules.values()) {
					if (recorded.id.equals(module.id)) { recordedId = true; break; }
				}
				if (!recordedId) legacyPrimary.addAll(module.primaryPaths);
			}
		}
		for (Module module : incoming) {
			validateModule(module);
			if (!prefix.isEmpty() && !under(module.manifestPath, prefix)) {
				throw new IOException("Incoming module is outside target prefix");
			}
			plan.next.modules.put(module.key(), module);
			if (full) {
				for (Module recorded : old.modules.values()) {
					if (recorded.id.equals(module.id)) selected.add(recorded.key());
				}
			}
		}
		if (wholeDirectory) {
			File item = target(prefix);
			if (exists(item)) {
				if (!item.isDirectory()) throw new IOException("Workshop item is not a directory: " + prefix);
				checkTree(item);
			}
		}
		if (!removal) {
			if (plan.staging == null || !plan.staging.isDirectory()) throw new IOException("Missing MOD staging directory");
			if (within(plan.staging, modsRoot) || within(modsRoot, plan.staging)
				|| within(plan.staging, registry) || within(registry, plan.staging)) {
				throw new IOException("Staging overlaps live files or receipts");
			}
			collectFiles(plan.staging, "", prefix, plan.incoming);
		}
		for (Map.Entry<String, File> item : plan.incoming.entrySet()) {
			String name = item.getKey();
			File live = target(name);
			checkTargetParents(name);
			if (exists(live)) {
				if (!live.isFile()) throw new IOException("Directory/file collision: " + name);
				Entry previous = old.files.get(name);
				boolean safe = previous != null && selected.containsAll(previous.owners) && unchanged(live, previous);
				if (previous == null && legacyPrimary.contains(name)) safe = true;
				if (!safe && !(wholeDirectory && under(name, prefix))) plan.conflicts.add(name);
			}
		}
		for (Map.Entry<String, Entry> item : old.files.entrySet()) {
			String name = item.getKey();
			Entry entry = item.getValue();
			if (wholeDirectory && under(name, prefix)) {
				if (removal) {
					File live = target(name);
					if (exists(live)) {
						if (!live.isFile()) throw new IOException("Tracked item file became a directory: " + name);
						if (!unchanged(live, entry)) {
							plan.preserved.add(name);
							plan.backupModified.add(name);
						}
					}
				}
				plan.next.files.remove(name);
				continue;
			}
			if (!full || Collections.disjoint(entry.owners, selected) || plan.incoming.containsKey(name)) continue;
			LinkedHashSet<String> remaining = new LinkedHashSet<>(entry.owners);
			remaining.removeAll(selected);
			if (!remaining.isEmpty()) {
				plan.next.files.put(name, new Entry(entry.length, entry.sha256, remaining));
				continue;
			}
			File live = target(name);
			if (!exists(live)) {
				plan.next.files.remove(name);
			} else {
				if (!live.isFile()) throw new IOException("Tracked file became a directory: " + name);
				boolean modified = !unchanged(live, entry);
				if (modified && !removal) {
					plan.preserved.add(name);
				} else {
					if (modified) {
						plan.preserved.add(name);
						plan.backupModified.add(name);
					}
					plan.delete.add(name);
					plan.next.files.remove(name);
				}
			}
		}
		if (full) {
			for (String name : legacyPrimary) {
				// A receipt always wins over legacy filename inference, including another owner's path.
				if (old.files.containsKey(name) || plan.incoming.containsKey(name)
					|| (wholeDirectory && under(name, prefix))) continue;
				File live = target(name);
				if (exists(live)) {
					if (!live.isFile()) throw new IOException("Legacy main path is a directory: " + name);
					plan.delete.add(name);
				}
			}
		}
		for (Map.Entry<String, File> item : plan.incoming.entrySet()) {
			String name = item.getKey();
			LinkedHashSet<String> owners = owners(name, incoming);
			Entry previous = old.files.get(name);
			Entry updated = fingerprint(item.getValue(), owners);
			if (previous != null && !(wholeDirectory && under(name, prefix)) && (owners.isEmpty()
				|| (!Collections.disjoint(previous.owners, owners)
					&& previous.length == updated.length && previous.sha256.equals(updated.sha256)))) {
				LinkedHashSet<String> retained = new LinkedHashSet<>(previous.owners);
				if (!owners.isEmpty()) retained.removeAll(selected);
				owners.addAll(retained);
			}
			if (!owners.isEmpty()) plan.next.files.put(name, new Entry(updated.length, updated.sha256, owners));
		}
		return plan;
	}

	private LinkedHashSet<String> owners(String name, List<Module> modules) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (Module module : modules) if (module.primaryPaths.contains(name)) result.add(module.key());
		if (!result.isEmpty()) return result;
		int closest = -1;
		for (Module module : modules) {
			String parent = parent(module.manifestPath);
			if (parent.isEmpty() || under(name, parent)) {
				int depth = parent.length();
				if (depth > closest) { result.clear(); closest = depth; }
				if (depth == closest) result.add(module.key());
			}
		}
		if (result.isEmpty()) for (Module module : modules) result.add(module.key());
		return result;
	}

	private Transaction execute(Plan plan, Lease lease) throws IOException {
		ensureDirectory(modsRoot);
		ensureDirectory(journal);
		File preservedDirectory = null;
		boolean ready = false;
		try {
			LinkedHashSet<String> operations = new LinkedHashSet<>();
			if (!plan.directory.isEmpty()) operations.add(plan.directory);
			operations.addAll(plan.delete);
			for (String name : plan.incoming.keySet()) {
				if (plan.directory.isEmpty() || !under(name, plan.directory)) operations.add(name);
			}
			LinkedHashSet<String> createdDirectories = new LinkedHashSet<>();
			JSONArray actions = new JSONArray();
			int ordinal = 0;
			for (String name : operations) {
				File live = target(name);
				boolean had = exists(live);
				JSONObject action = new JSONObject();
				action.put("path", name);
				action.put("had", had);
				action.put("directory", name.equals(plan.directory));
				action.put("backup", Integer.toString(ordinal));
				if (had) {
					File backup = new File(journal, "backup/" + ordinal);
					copyTree(live, backup);
					action.put("backupDigest", treeDigest(backup));
				}
				actions.put(action);
				ordinal++;
				String ancestor = parent(name);
				while (!ancestor.isEmpty()) {
					if (!exists(target(ancestor))) createdDirectories.add(ancestor);
					ancestor = parent(ancestor);
				}
			}
			copyFile(index, new File(journal, "old-index.json"));
			writeSynced(new File(journal, "new-index.json"), serialize(plan.next));
			if (!plan.backupModified.isEmpty()) {
				preservedDirectory = new File(registry, "preserved/" + UUID.randomUUID());
				ensureDirectory(preservedDirectory);
				for (String name : plan.backupModified) copyFile(target(name), new File(preservedDirectory, name));
			}
			JSONObject metadata = new JSONObject();
			metadata.put("schema", 1);
			metadata.put("scope", scope);
			metadata.put("oldIndexSha256", fingerprint(new File(journal, "old-index.json"), Collections.emptySet()).sha256);
			metadata.put("newIndexSha256", fingerprint(new File(journal, "new-index.json"), Collections.emptySet()).sha256);
			metadata.put("actions", actions);
			metadata.put("createdDirectories", new JSONArray(createdDirectories));
			writeSynced(new File(journal, "journal.json"), bytes(metadata.toString()));
			if (!plan.directory.isEmpty() && plan.staging != null) syncTree(plan.staging);
			atomicWrite(new File(journal, "phase"), bytes("READY"));
			ready = true;
			if (!plan.directory.isEmpty()) {
				File destination = target(plan.directory);
				if (exists(destination)) {
					File displaced = new File(journal, "displaced-item");
					checkPath(displaced);
					if (!destination.renameTo(displaced)) throw new IOException("Unable to stage existing Workshop item");
				}
				if (plan.staging != null) {
					checkTree(plan.staging);
					ensureDirectory(destination.getParentFile());
					if (!plan.staging.renameTo(destination)) throw new IOException("Unable to publish Workshop item");
				}
			}
			for (String name : plan.delete) {
				File destination = target(name);
				if (exists(destination) && !destination.delete()) throw new IOException("Unable to remove " + name);
			}
			pruneEmptyParents(plan.delete);
			if (plan.directory.isEmpty()) {
				for (Map.Entry<String, File> item : plan.incoming.entrySet()) copyFile(item.getValue(), target(item.getKey()));
			}
			publishIndex(new File(journal, "new-index.json"));
			return new Transaction(plan, lease, preservedDirectory);
		} catch (JSONException e) {
			IOException failure = corrupt("journal", e);
			try { if (ready) recoverLocked(); else cleanupJournal(); } catch (IOException rollback) { failure.addSuppressed(rollback); }
			throw failure;
		} catch (IOException | RuntimeException e) {
			try { if (ready) recoverLocked(); else cleanupJournal(); } catch (IOException rollback) { e.addSuppressed(rollback); }
			throw e;
		}
	}

	private void recoverLocked() throws IOException {
		try {
			if (!exists(journal)) return;
		} catch (IOException e) {
			ModManifestCache.invalidatePath(modsRoot);
			throw e;
		}
		try {
			recoverJournal();
		} finally {
			ModManifestCache.invalidatePath(modsRoot);
		}
	}

	private void recoverJournal() throws IOException {
		if (!journal.isDirectory()) throw new IOException("Invalid MOD transaction directory");
		checkTree(journal);
		File phase = new File(journal, "phase");
		if (!exists(phase)) { cleanupJournal(); return; }
		String status = text(phase);
		if ("COMMITTED".equals(status)) { cleanupJournal(); return; }
		if (!"READY".equals(status)) throw new IOException("Corrupt MOD transaction phase");
		JSONObject metadata = json(new File(journal, "journal.json"));
		try {
			if (metadata.getInt("schema") != 1 || !scope.equals(metadata.getString("scope"))) {
				throw new IOException("Corrupt MOD transaction scope");
			}
			JSONArray actions = metadata.getJSONArray("actions");
			// Validate the complete recovery input before changing any live files.
			LinkedHashSet<String> names = new LinkedHashSet<>();
			for (int i = 0; i < actions.length(); i++) {
				JSONObject action = actions.getJSONObject(i);
				String name = path(action.getString("path"), false);
				if (!names.add(name)) throw new IOException("Duplicate journal action");
				for (String previous : names) {
					if (!previous.equals(name) && (under(previous, name) || under(name, previous))) {
						throw new IOException("Overlapping journal actions");
					}
				}
				if (!Integer.toString(i).equals(action.getString("backup"))) throw new IOException("Corrupt journal backup");
				boolean directory = action.getBoolean("directory");
				File live = target(name);
				if (exists(live) && (directory ? !live.isDirectory() : !live.isFile())) {
					throw new IOException("Recovery path changed type: " + name);
				}
				if (directory && exists(live)) checkTree(live);
				if (action.getBoolean("had")) {
					File backup = new File(journal, "backup/" + i);
					if (!exists(backup) || (directory ? !backup.isDirectory() : !backup.isFile())) {
						throw new IOException("Missing journal backup: " + name);
					}
					if (!treeDigest(backup).equals(action.getString("backupDigest"))) {
						throw new IOException("Corrupt journal backup bytes: " + name);
					}
				}
			}
			File oldIndex = new File(journal, "old-index.json");
			File newIndex = new File(journal, "new-index.json");
			readState(oldIndex);
			readState(newIndex);
			if (!fingerprint(oldIndex, Collections.emptySet()).sha256.equals(metadata.getString("oldIndexSha256"))
				|| !fingerprint(newIndex, Collections.emptySet()).sha256.equals(metadata.getString("newIndexSha256"))) {
				throw new IOException("Corrupt journal receipt snapshot");
			}
			JSONArray created = metadata.getJSONArray("createdDirectories");
			List<String> createdPaths = new ArrayList<>();
			for (int i = 0; i < created.length(); i++) {
				String name = path(created.getString(i), false);
				boolean ancestor = false;
				for (String action : names) {
					if (!action.equals(name) && under(action, name)) { ancestor = true; break; }
				}
				if (!ancestor) throw new IOException("Invalid journal created-directory path");
				File directory = target(name);
				if (exists(directory) && !directory.isDirectory()) throw new IOException("Recovery parent changed type");
				createdPaths.add(name);
			}
			for (int i = 0; i < actions.length(); i++) {
				JSONObject action = actions.getJSONObject(i);
				File live = target(action.getString("path"));
				if (exists(live)) deleteTree(live);
				if (action.getBoolean("had")) copyTree(new File(journal, "backup/" + i), live);
			}
			publishIndex(oldIndex);
			Collections.sort(createdPaths, (a, b) -> Integer.compare(b.length(), a.length()));
			for (String name : createdPaths) {
				File directory = target(name);
				if (exists(directory) && directory.isDirectory()) {
					String[] children = directory.list();
					if (children == null) throw new IOException("Unable to inspect restored directory");
					if (children.length == 0 && !directory.delete()) throw new IOException("Unable to remove transaction directory");
				}
			}
			cleanupJournal();
		} catch (JSONException | IllegalArgumentException e) { throw corrupt("journal", e); }
	}

	private void cleanupJournal() throws IOException {
		if (!exists(journal)) return;
		checkTree(journal);
		// Removing the marker first is safe only after rollback, commit, or before READY.
		// A cleanup interrupted later is recognized as an unstarted transaction.
		File phase = new File(journal, "phase");
		if (exists(phase) && !phase.delete()) throw new IOException("Unable to clear transaction phase");
		deleteTree(journal);
	}

	private State readState(File file) throws IOException {
		State state = new State();
		if (!exists(file)) throw new IOException("Missing MOD receipt index or backup: " + file);
		JSONObject object = json(file);
		try {
			if (object.getInt("schema") != 1 || !scope.equals(object.getString("scope"))) throw new IOException("Corrupt MOD receipt scope");
			JSONArray modules = object.getJSONArray("modules");
			for (int i = 0; i < modules.length(); i++) {
				JSONObject record = modules.getJSONObject(i);
				JSONArray primary = record.getJSONArray("primary");
				List<String> paths = new ArrayList<>();
				for (int p = 0; p < primary.length(); p++) paths.add(path(primary.getString(p), false));
				Module module = new Module(record.getString("id"), record.getString("manifest"), record.getString("version"), paths);
				if (state.modules.put(module.key(), module) != null) throw new IOException("Duplicate module receipt");
			}
			JSONArray files = object.getJSONArray("files");
			for (int i = 0; i < files.length(); i++) {
				JSONObject record = files.getJSONObject(i);
				String name = path(record.getString("path"), false);
				long length = record.getLong("length");
				String hash = record.getString("sha256");
				if (length < 0 || !SHA256_HEX.matcher(hash).matches()) throw new IOException("Invalid receipt fingerprint");
				LinkedHashSet<String> owners = new LinkedHashSet<>();
				JSONArray ownerArray = record.getJSONArray("owners");
				for (int o = 0; o < ownerArray.length(); o++) {
					String key = ownerArray.getString(o);
					if (!state.modules.containsKey(key) || !owners.add(key)) throw new IOException("Invalid receipt owner");
				}
				if (owners.isEmpty() || state.files.put(name, new Entry(length, hash, owners)) != null) throw new IOException("Invalid receipt path");
			}
			return state;
		} catch (JSONException | IllegalArgumentException e) { throw corrupt("index", e); }
	}

	private byte[] serialize(State state) throws IOException {
		try {
			JSONObject object = new JSONObject();
			object.put("schema", 1);
			object.put("scope", scope);
			JSONArray modules = new JSONArray();
			for (Module module : state.modules.values()) {
				JSONObject record = new JSONObject();
				record.put("id", module.id);
				record.put("manifest", module.manifestPath);
				record.put("version", module.version);
				record.put("primary", new JSONArray(module.primaryPaths));
				modules.put(record);
			}
			object.put("modules", modules);
			JSONArray files = new JSONArray();
			for (Map.Entry<String, Entry> item : state.files.entrySet()) {
				JSONObject record = new JSONObject();
				record.put("path", item.getKey());
				record.put("length", item.getValue().length);
				record.put("sha256", item.getValue().sha256);
				record.put("owners", new JSONArray(item.getValue().owners));
				files.put(record);
			}
			object.put("files", files);
			return bytes(object.toString());
		} catch (JSONException e) { throw corrupt("index", e); }
	}

	private State copyState(State state) {
		State result = new State();
		result.modules.putAll(state.modules);
		result.files.putAll(state.files);
		return result;
	}

	private void validateModule(Module module) throws IOException {
		if (module == null) throw new IOException("Missing module metadata");
		path(module.manifestPath, false);
		for (String primary : module.primaryPaths) path(primary, false);
	}

	private File target(String relative) throws IOException {
		File file = new File(modsRoot, path(relative, false));
		checkPath(file);
		return file;
	}

	private void checkTargetParents(String relative) throws IOException {
		String ancestor = parent(relative);
		while (!ancestor.isEmpty()) {
			File directory = target(ancestor);
			if (exists(directory) && !directory.isDirectory()) throw new IOException("File/directory collision: " + relative);
			ancestor = parent(ancestor);
		}
	}

	private void pruneEmptyParents(Collection<String> deletedFiles) throws IOException {
		Set<String> candidates = new LinkedHashSet<>();
		for (String name : deletedFiles) {
			String ancestor = parent(name);
			while (!ancestor.isEmpty()) {
				candidates.add(ancestor);
				ancestor = parent(ancestor);
			}
		}
		List<String> deepestFirst = new ArrayList<>(candidates);
		Collections.sort(deepestFirst, (a, b) -> Integer.compare(b.length(), a.length()));
		for (String name : deepestFirst) {
			File directory = target(name);
			if (!exists(directory)) continue;
			if (!directory.isDirectory()) throw new IOException("Expected tracked file parent directory: " + name);
			String[] children = directory.list();
			if (children == null) throw new IOException("Unable to inspect tracked file parent: " + name);
			if (children.length == 0 && !directory.delete()) throw new IOException("Unable to prune empty MOD directory: " + name);
		}
	}

	private File normalizeStagingRoot(File directory) throws IOException {
		File absolute = directory.getAbsoluteFile();
		File normalized = absolute;
		// Only the trusted application boundary may resolve an Android OS alias.
		// Cache/staging ancestors below that boundary still cannot be symlinks.
		if (within(absolute, lexicalAppRoot)) {
			if (absolute.equals(lexicalAppRoot)) throw new IOException("Invalid staging root");
			String relative = absolute.getPath().substring(lexicalAppRoot.getPath().length() + 1);
			path(relative.replace(File.separatorChar, '/'), false);
			normalized = new File(trustedAppRoot, relative);
		}
		checkPath(normalized);
		return normalized;
	}

	private static void syncTree(File file) throws IOException {
		checkPath(file);
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children == null) throw new IOException("Unable to sync staging tree");
			for (File child : children) syncTree(child);
		} else {
			if (!file.isFile()) throw new IOException("Not a staged file: " + file);
			try (RandomAccessFile handle = new RandomAccessFile(file, "rw")) { handle.getFD().sync(); }
		}
	}

	private static String checkedPath(String value, boolean empty) {
		if (value == null || (!empty && value.isEmpty()) || value.startsWith("/")
			|| value.indexOf('\\') >= 0 || value.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Invalid relative MOD path");
		if (value.isEmpty()) return value;
		for (String part : value.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid relative MOD path");
		}
		return value;
	}

	private static String path(String value, boolean empty) throws IOException {
		try { return checkedPath(value, empty); }
		catch (IllegalArgumentException e) { throw new IOException("Unsafe relative MOD path", e); }
	}

	private static String parent(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	private static boolean under(String path, String directory) {
		return path.equals(directory) || path.startsWith(directory + "/");
	}

	private static boolean within(File child, File parent) {
		return child.equals(parent) || child.getPath().startsWith(parent.getPath() + File.separator);
	}

	private static void collectFiles(File directory, String relative, String prefix, Map<String, File> result) throws IOException {
		File[] children = directory.listFiles();
		if (children == null) throw new IOException("Unable to list staging directory: " + directory);
		for (File child : children) {
			checkPath(child);
			String name = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
			path(name, false);
			if (child.isDirectory()) collectFiles(child, name, prefix, result);
			else if (child.isFile()) result.put(prefix.isEmpty() ? name : prefix + "/" + name, child);
			else throw new IOException("Unsupported staged file: " + child);
		}
	}

	private static String treeDigest(File root) throws IOException {
		MessageDigest digest = sha256();
		updateTreeDigest(root, "", digest);
		return hex(digest.digest());
	}

	private static void updateTreeDigest(File file, String relative, MessageDigest digest) throws IOException {
		checkPath(file);
		if (file.isDirectory()) {
			digest.update(bytes("D\u0000" + relative + "\u0000"));
			File[] children = file.listFiles();
			if (children == null) throw new IOException("Unable to fingerprint backup directory");
			java.util.Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
			for (File child : children) {
				String name = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
				path(name, false);
				updateTreeDigest(child, name, digest);
			}
		} else {
			Entry entry = fingerprint(file, Collections.emptySet());
			digest.update(bytes("F\u0000" + relative + "\u0000" + entry.length + "\u0000" + entry.sha256 + "\u0000"));
		}
	}

	private static Entry fingerprint(File file, Collection<String> owners) throws IOException {
		checkPath(file);
		if (!file.isFile()) throw new IOException("Not a regular file: " + file);
		MessageDigest digest = sha256();
		long length = 0;
		byte[] buffer = new byte[32768];
		try (FileInputStream input = new FileInputStream(file)) {
			int count;
			while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); length += count; }
		}
		return new Entry(length, hex(digest.digest()), owners);
	}

	private static boolean unchanged(File file, Entry entry) throws IOException {
		if (file.length() != entry.length) return false;
		return fingerprint(file, Collections.emptySet()).sha256.equals(entry.sha256);
	}

	private static MessageDigest sha256() {
		try { return MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
	}

	private static String digest(byte[] bytes) { return hex(sha256().digest(bytes)); }

	private static String hex(byte[] bytes) {
		char[] result = new char[bytes.length * 2];
		char[] alphabet = "0123456789abcdef".toCharArray();
		for (int i = 0; i < bytes.length; i++) {
			result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
			result[i * 2 + 1] = alphabet[bytes[i] & 15];
		}
		return new String(result);
	}

	private static List<String> immutable(Collection<String> values) {
		return Collections.unmodifiableList(new ArrayList<>(values));
	}

	private static IOException corrupt(String what, Exception cause) { return new IOException("Corrupt MOD " + what, cause); }
	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

	/** lstat never follows the final component; absence is distinct from a dangling link or I/O error. */
	static int modeNoFollow(File file) throws IOException {
		try {
			return Os.lstat(file.getPath()).st_mode;
		} catch (ErrnoException e) {
			if (e.errno == OsConstants.ENOENT) return 0;
			throw new IOException("Unable to inspect path: " + file, e);
		}
	}

	/** Reject symlinks in every ancestor, including dangling directory entries. */
	private static int checkPath(File file) throws IOException {
		File absolute = file.getAbsoluteFile();
		File parent = absolute.getParentFile();
		if (parent != null) {
			int parentMode = checkPath(parent);
			if (parentMode != 0 && !OsConstants.S_ISDIR(parentMode)) throw new IOException("Not a directory: " + parent);
		}
		if (".".equals(absolute.getName()) || "..".equals(absolute.getName())) throw new IOException("Non-normal path: " + file);
		int mode = modeNoFollow(absolute);
		if (mode != 0 && !OsConstants.S_ISREG(mode) && !OsConstants.S_ISDIR(mode)) {
			throw new IOException("Symlink or unsupported filesystem entry: " + file);
		}
		return mode;
	}

	private static boolean exists(File file) throws IOException { return checkPath(file) != 0; }

	private static void checkTree(File file) throws IOException {
		checkPath(file);
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children == null) throw new IOException("Unable to inspect directory: " + file);
			for (File child : children) checkTree(child);
		} else if (!file.isFile()) throw new IOException("Missing filesystem entry: " + file);
	}

	private static void ensureDirectory(File directory) throws IOException {
		checkPath(directory);
		if (directory.isDirectory()) return;
		if (directory.exists()) throw new IOException("Expected directory: " + directory);
		File parent = directory.getParentFile();
		if (parent != null) ensureDirectory(parent);
		if (!directory.mkdir() && !directory.isDirectory()) throw new IOException("Unable to create directory: " + directory);
		checkPath(directory);
	}

	private static void copyTree(File source, File destination) throws IOException {
		checkPath(source);
		checkPath(destination);
		if (source.isDirectory()) {
			ensureDirectory(destination);
			File[] children = source.listFiles();
			if (children == null) throw new IOException("Unable to copy directory: " + source);
			for (File child : children) copyTree(child, new File(destination, child.getName()));
		} else copyFile(source, destination);
	}

	private static void copyFile(File source, File destination) throws IOException {
		checkPath(source);
		checkPath(destination);
		if (!source.isFile()) throw new IOException("Expected source file: " + source);
		ensureDirectory(destination.getParentFile());
		if (destination.exists() && !destination.isFile()) throw new IOException("Expected destination file: " + destination);
		byte[] buffer = new byte[32768];
		try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
			int count;
			while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
			output.getFD().sync();
		}
	}

	private static void deleteTree(File file) throws IOException {
		checkPath(file);
		if (!file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children == null) throw new IOException("Unable to delete directory: " + file);
			for (File child : children) deleteTree(child);
		}
		if (!file.delete()) throw new IOException("Unable to delete: " + file);
	}

	private static void writeSynced(File file, byte[] content) throws IOException {
		checkPath(file);
		ensureDirectory(file.getParentFile());
		if (file.exists() && !file.isFile()) throw new IOException("Expected file: " + file);
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(content);
			output.getFD().sync();
		}
	}

	private static void atomicWrite(File file, byte[] content) throws IOException {
		File temporary = new File(file.getParentFile(), file.getName() + ".next");
		writeSynced(temporary, content);
		checkPath(file);
		if (!temporary.renameTo(file)) throw new IOException("Unable to publish file: " + file);
	}

	private void publishIndex(File source) throws IOException {
		File temporary = new File(registry, "index.next");
		copyFile(source, temporary);
		checkPath(index);
		if (!temporary.renameTo(index)) throw new IOException("Unable to publish MOD receipt");
	}

	private static String text(File file) throws IOException {
		checkPath(file);
		if (!file.isFile()) throw new IOException("Missing metadata file: " + file);
		try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static JSONObject json(File file) throws IOException {
		try { return new JSONObject(text(file)); }
		catch (JSONException e) { throw corrupt("JSON metadata", e); }
	}

	private Lease lock() throws IOException {
		checkPath(registry);
		File lockFile = new File(registry, "lock");
		if (!HELD_LOCK_FILES.add(lockFile)) throw new BusyException(null);
		RandomAccessFile file = null;
		try {
			checkPath(lockFile);
			file = new RandomAccessFile(lockFile, "rw");
			FileLock lock;
			try { lock = file.getChannel().tryLock(); }
			catch (OverlappingFileLockException e) { throw new BusyException(e); }
			if (lock == null) throw new BusyException(null);
			// Each metadata/backup path is validated before use. Do not walk unrelated
			// preserved assets (or a pending journal) just to acquire a clean readiness lease.
			return new Lease(lockFile, file, lock);
		} catch (IOException | RuntimeException | Error e) {
			try { if (file != null) file.close(); } catch (IOException close) { e.addSuppressed(close); }
			finally { HELD_LOCK_FILES.remove(lockFile); }
			throw e;
		}
	}

	private static final class Lease implements AutoCloseable {
		private final File lockFile;
		private final RandomAccessFile file;
		private final FileLock lock;
		private boolean closed;

		Lease(File lockFile, RandomAccessFile file, FileLock lock) {
			this.lockFile = lockFile;
			this.file = file;
			this.lock = lock;
		}

		@Override public void close() throws IOException {
			if (closed) return;
			closed = true;
			try {
				try { lock.release(); } finally { file.close(); }
			} finally { HELD_LOCK_FILES.remove(lockFile); }
		}
	}
}
