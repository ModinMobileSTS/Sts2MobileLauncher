package com.godot.game;

import java.io.File;
import java.io.IOException;

final class DirectoryStatsCalculator {
	private DirectoryStatsCalculator() {
	}

	static DirectoryStats calculate(File root) {
		DirectoryStats stats = new DirectoryStats();
		collect(root, stats);
		return stats;
	}

	private static void collect(File file, DirectoryStats stats) {
		if (file == null || !file.exists() || isSymbolicLink(file)) {
			return;
		}
		if (file.isFile()) {
			stats.fileCount++;
			stats.totalBytes += Math.max(0L, file.length());
			return;
		}
		File[] children = file.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			collect(child, stats);
		}
	}

	private static boolean isSymbolicLink(File file) {
		try {
			File fileInCanonicalParent;
			File parent = file.getParentFile();
			if (parent == null) {
				fileInCanonicalParent = file;
			} else {
				fileInCanonicalParent = new File(parent.getCanonicalFile(), file.getName());
			}
			return !fileInCanonicalParent.getCanonicalFile().equals(fileInCanonicalParent.getAbsoluteFile());
		} catch (IOException ignored) {
			return false;
		}
	}

	static final class DirectoryStats {
		int fileCount;
		long totalBytes;
	}
}
