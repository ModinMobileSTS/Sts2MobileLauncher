package com.godot.game;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Latest live runtime logs; archives are deliberately not candidates. Call off the UI thread. */
public final class RuntimeLogFiles {
    private RuntimeLogFiles() {
    }

    public static List<File> latest(Context context) {
        return latest(context.getFilesDir(), context.getExternalFilesDir(null));
    }

    public static List<File> latest(File internalRoot, File externalRoot) {
        List<File> candidates = new ArrayList<>();
        collect(internalRoot, candidates);
        collect(externalRoot, candidates);
        return selectLatest(candidates);
    }

    public static List<File> selectLatest(List<File> candidates) {
        File godot = null;
        File sts2 = null;
        for (File file : candidates) {
            if (file == null || !file.isFile()) {
                continue;
            }
            if ("godot.log".equals(file.getName()) && newer(file, godot)) {
                godot = file;
            } else if ("sts2.log".equals(file.getName()) && newer(file, sts2)) {
                sts2 = file;
            }
        }
        List<File> latest = new ArrayList<>(2);
        if (godot != null) {
            latest.add(godot);
        }
        if (sts2 != null) {
            latest.add(sts2);
        }
        return latest;
    }

    private static boolean newer(File candidate, File previous) {
        if (previous == null) {
            return true;
        }
        long candidateTime = candidate.lastModified();
        long previousTime = previous.lastModified();
        return candidateTime > previousTime || (candidateTime == previousTime
                && candidate.getAbsolutePath().compareTo(previous.getAbsolutePath()) < 0);
    }

    private static void collect(File root, List<File> candidates) {
        if (root == null || !root.isDirectory()) {
            return;
        }
        try {
            ArrayDeque<File> pending = new ArrayDeque<>();
            pending.add(root.getCanonicalFile());
            while (!pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
                File[] children = pending.removeFirst().listFiles();
                if (children == null) {
                    continue;
                }
                for (File child : children) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (child.isDirectory()) {
                        // Directory links remain excluded, including links within the root.
                        if (child.getAbsoluteFile().equals(child.getCanonicalFile())) {
                            pending.addLast(child);
                        }
                    } else if (("godot.log".equals(child.getName()) || "sts2.log".equals(child.getName()))
                            && child.isFile() && child.getAbsoluteFile().equals(child.getCanonicalFile())) {
                        candidates.add(child);
                    }
                }
            }
        } catch (IOException ignored) {
            // An unavailable root contributes no candidate; the other root remains usable.
        }
    }
}
