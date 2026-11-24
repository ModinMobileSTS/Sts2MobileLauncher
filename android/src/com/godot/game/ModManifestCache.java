package com.godot.game;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Process-local display data only. Never consult this cache when planning or applying writes. */
final class ModManifestCache {
	interface Scan {
		List<ExtraSettingsRepository.ModEntry> read() throws IOException;
	}

	// A slot with no snapshot is owned by exactly one scanning worker. Keep it until the
	// scan exits, even on invalidation, so racing readers cannot start another walk of that root.
	private static final class Slot {
		List<ExtraSettingsRepository.ModEntry> snapshot;
		boolean invalidated;
	}

	private static final Map<File, Slot> roots = new HashMap<>();

	private ModManifestCache() {}

	static List<ExtraSettingsRepository.ModEntry> get(File root, Scan scan) throws IOException {
		for (;;) {
			File key = root.getCanonicalFile(); // Identity only; never use a resolved symlink for traversal.
			Slot slot;
			synchronized (roots) {
				slot = roots.get(key);
				if (slot != null) {
					if (slot.snapshot != null) return slot.snapshot;
					try {
						roots.wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while waiting for MOD manifests", e);
					}
					continue;
				}
				slot = new Slot();
				roots.put(key, slot);
			}
			List<ExtraSettingsRepository.ModEntry> snapshot = null;
			try {
				snapshot = Collections.unmodifiableList(new ArrayList<>(scan.read()));
			} finally {
				synchronized (roots) {
					if (slot.invalidated || snapshot == null) {
						roots.remove(key);
						snapshot = null;
					} else {
						slot.snapshot = snapshot;
					}
					roots.notifyAll();
				}
			}
			if (snapshot != null) return snapshot;
			// A writer invalidated us while the filesystem scan was in flight. Retry instead
			// of returning its pre-write data to this reader or publishing it for other pages.
		}
	}

	static void invalidatePath(File changed) {
		if (changed == null) return;
		synchronized (roots) {
			if (roots.isEmpty()) return;
		}
		try {
			File entry = changed.getAbsoluteFile();
			File parent = entry.getParentFile();
			// A changed directory entry may itself be a link out of MODs. Resolve the
			// Android spelling of its parent without losing the entry that was changed.
			File inResolvedParent = parent == null ? entry : new File(parent.getCanonicalFile(), entry.getName());
			invalidate(entry, inResolvedParent, entry.getCanonicalFile());
		} catch (IOException | SecurityException e) {
			// An unreadable/replaced ancestor must not strand an old snapshot under an alias.
			invalidateAll();
		}
	}

	static void invalidateAll() {
		invalidate(null, null, null);
	}

	private static void invalidate(File path, File inResolvedParent, File resolved) {
		synchronized (roots) {
			Iterator<Map.Entry<File, Slot>> iterator = roots.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<File, Slot> entry = iterator.next();
				File root = entry.getKey();
				if (path != null && !intersects(root, path) && !intersects(root, inResolvedParent)
					&& !intersects(root, resolved)) continue;
				if (entry.getValue().snapshot == null) entry.getValue().invalidated = true;
				else iterator.remove();
			}
		}
	}

	private static boolean intersects(File a, File b) {
		String first = a.getPath(), second = b.getPath();
		return within(first, second) || within(second, first);
	}

	private static boolean within(String path, String root) {
		return path.equals(root) || (path.startsWith(root) && (root.equals(File.separator)
			|| path.charAt(root.length()) == File.separatorChar));
	}
}
