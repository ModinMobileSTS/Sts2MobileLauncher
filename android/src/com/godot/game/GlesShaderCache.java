package com.godot.game;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Reversible disk-cache workaround for the prebuilt Godot 4.5 GLES renderer. */
final class GlesShaderCache {
	enum Mode {
		OFF("off"), PARTICLES("particles"), ALL("all");

		final String value;
		Mode(String value) { this.value = value; }

		static Mode fromValue(String value) {
			if (PARTICLES.value.equals(value)) return PARTICLES;
			if (ALL.value.equals(value)) return ALL;
			return OFF;
		}
	}

	// A manual #136 workaround is an empty file. Only undo our own tagged files.
	private static final long BLOCKER_TAG = 0x53545332474c5333L; // STS2GLS3

	private GlesShaderCache() {}

	static void apply(File files, String renderer, Mode requested) throws IOException {
		Mode mode = RendererPreference.RENDERER_OPENGL_ES3.equals(renderer) ? requested : Mode.OFF;
		File state = new File(files, ".sts2-gles-cache");
		try {
			if (mode == Mode.OFF && stat(state) == null) return;
			ensureDirectory(state);
			File root = new File(files, "shader_cache");
			reconcile(root, new File(state, "all"), mode == Mode.ALL);
			if (mode == Mode.ALL) return;
			File savedParticles = new File(state, "particles");
			StructStat rootStat = stat(root);
			if (rootStat == null) {
				if (mode != Mode.PARTICLES && stat(savedParticles) == null) return;
				ensureDirectory(root);
			} else if (!OsConstants.S_ISDIR(rootStat.st_mode)) {
				if (OsConstants.S_ISREG(rootStat.st_mode)) return; // Externally blocked root.
				throw new IOException("Not a cache directory: " + root);
			}
			reconcile(new File(root, "ParticlesShaderGLES3"), savedParticles, mode == Mode.PARTICLES);
		} catch (ErrnoException e) {
			throw new IOException("Cannot apply GLES shader cache mode " + mode.value, e);
		}
	}

	private static void reconcile(File live, File saved, boolean block) throws ErrnoException, IOException {
		StructStat current = stat(live);
		StructStat backup = stat(saved);
		if (backup != null && !OsConstants.S_ISDIR(backup.st_mode)) throw new IOException("Invalid parked cache: " + saved);
		if (block) {
			if (current != null) {
				if (OsConstants.S_ISREG(current.st_mode)) return; // Already blocked, possibly by the user.
				if (!OsConstants.S_ISDIR(current.st_mode) || backup != null) throw new IOException("Cannot park cache: " + live);
			}
			// Publish a complete marker by rename. Android app SELinux policies can deny hard links.
			File tmp = File.createTempFile("blocker-", ".tmp", saved.getParentFile());
			try {
				try (DataOutputStream output = new DataOutputStream(new FileOutputStream(tmp))) {
					output.writeLong(BLOCKER_TAG);
				}
				if (current != null) Os.rename(live.getPath(), saved.getPath());
				try {
					Os.rename(tmp.getPath(), live.getPath());
				} catch (ErrnoException e) {
					if (stat(live) == null && stat(saved) != null) Os.rename(saved.getPath(), live.getPath());
					throw e;
				}
			} finally {
				if (tmp.exists()) Os.remove(tmp.getPath());
			}
		} else {
			boolean ours = isBlocker(live, current);
			if (ours) Os.remove(live.getPath());
			if (backup != null) {
				// Also recover if a previous launch stopped between the two renames.
				if (!ours && current != null) throw new IOException("Keeping both existing and parked cache: " + live);
				Os.rename(saved.getPath(), live.getPath());
			}
		}
	}

	private static boolean isBlocker(File live, StructStat info) throws IOException {
		if (info == null || !OsConstants.S_ISREG(info.st_mode) || info.st_size != Long.BYTES) return false;
		try (DataInputStream input = new DataInputStream(new FileInputStream(live))) {
			return input.readLong() == BLOCKER_TAG;
		}
	}

	private static void ensureDirectory(File dir) throws ErrnoException, IOException {
		if (dir.mkdir()) return;
		if (!OsConstants.S_ISDIR(Os.lstat(dir.getPath()).st_mode)) throw new IOException("Not a directory: " + dir);
	}

	private static StructStat stat(File file) throws ErrnoException {
		try {
			return Os.lstat(file.getPath());
		} catch (ErrnoException e) {
			if (e.errno == OsConstants.ENOENT) return null;
			throw e;
		}
	}
}
