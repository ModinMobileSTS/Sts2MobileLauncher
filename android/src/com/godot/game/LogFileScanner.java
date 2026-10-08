package com.godot.game;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Iterative, unsorted log tree traversal. Call from a worker thread; the caller sorts the result.
 * Only log-named files and files below a logs or sentry/reports directory are candidates.
 */
final class LogFileScanner {
    static final int BATCH_SIZE = 32;

    interface BatchConsumer {
        void onBatch(List<Entry> entries);
    }

    static final class Root {
        final File directory;
        final String archiveRootName;
        final String displayRootName;

        Root(File directory, String archiveRootName, String displayRootName) {
            this.directory = directory;
            this.archiveRootName = archiveRootName;
            this.displayRootName = displayRootName;
        }
    }

    static final class Entry {
        final File file;
        final String relativePath;
        final String displayPath;
        final String archivePath;
        final String storageLabel;
        final long lastModified;
        final long size;

        Entry(File file, String relativePath, String displayPath, String archivePath, String storageLabel) {
            this.file = file;
            this.relativePath = relativePath;
            this.displayPath = displayPath;
            this.archivePath = archivePath;
            this.storageLabel = storageLabel;
            this.lastModified = file.lastModified();
            this.size = file.length();
        }
    }

    private LogFileScanner() {
    }

    static void scan(BatchConsumer consumer, Root... roots) {
        Scan scan = new Scan(consumer);
        for (Root root : roots) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (root != null && root.directory != null && root.directory.isDirectory()) {
                scan.collect(root);
            }
        }
        scan.publish();
    }

    private static final class Scan {
        private static final long MAX_BATCH_DELAY_NANOS = 150_000_000L;
        final BatchConsumer consumer;
        final Set<String> seenPaths = new HashSet<>();
        List<Entry> batch = new ArrayList<>(BATCH_SIZE);
        long lastPublishNanos;
        boolean published;

        Scan(BatchConsumer consumer) {
            this.consumer = consumer;
        }

        void collect(Root root) {
            String canonicalRoot = canonicalPath(root.directory);
            Set<String> visitedDirectories = new HashSet<>();
            visitedDirectories.add(canonicalRoot);
            ArrayDeque<DirectoryState> preferred = new ArrayDeque<>();
            ArrayDeque<DirectoryState> pending = new ArrayDeque<>();
            preferred.addLast(new DirectoryState(root.directory, "", false));

            while ((!preferred.isEmpty() || !pending.isEmpty()) && !Thread.currentThread().isInterrupted()) {
                DirectoryState current = preferred.isEmpty() ? pending.removeLast() : preferred.removeLast();
                File[] children = current.directory.listFiles();
                if (children == null) {
                    continue;
                }
                for (File child : children) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (!batch.isEmpty() && System.nanoTime() - lastPublishNanos >= MAX_BATCH_DELAY_NANOS) {
                        publish();
                    }
                    if (child.isDirectory()) {
                        String childCanonical = canonicalPath(child);
                        if (!isWithinRoot(canonicalRoot, childCanonical) || !visitedDirectories.add(childCanonical)) {
                            continue;
                        }
                        String relativePath = appendPath(current.relativePath, child.getName());
                        boolean allFilesRelevant = current.allFilesRelevant || isLogDirectory(relativePath);
                        DirectoryState next = new DirectoryState(child, relativePath, allFilesRelevant);
                        // Prefer known log locations without excluding any other subtree.
                        boolean prioritize = allFilesRelevant || child.getName().equalsIgnoreCase("sentry")
                                || relativePath.equalsIgnoreCase("instances")
                                || current.relativePath.equalsIgnoreCase("instances");
                        (prioritize ? preferred : pending).addLast(next);
                        continue;
                    }

                    String fileName = child.getName();
                    if ((!current.allFilesRelevant && !isLogFileName(fileName)) || !child.isFile()) {
                        continue;
                    }
                    if (!seenPaths.add(canonicalPath(child))) {
                        continue;
                    }
                    String relativePath = appendPath(current.relativePath, fileName);
                    batch.add(new Entry(
                            child,
                            relativePath,
                            appendPath(root.displayRootName, relativePath),
                            appendPath(root.archiveRootName, relativePath),
                            root.displayRootName));
                    // A handful of logs must not wait for a full batch or a huge unrelated tree.
                    if (!published || batch.size() >= BATCH_SIZE) {
                        publish();
                    }
                }
            }
        }

        void publish() {
            if (batch.isEmpty()) {
                return;
            }
            List<Entry> ready = batch;
            batch = new ArrayList<>(BATCH_SIZE);
            published = true;
            lastPublishNanos = System.nanoTime();
            consumer.onBatch(ready);
        }
    }

    private static boolean isLogFileName(String name) {
        return name != null
                && name.length() >= 4
                && name.regionMatches(true, name.length() - 4, ".log", 0, 4);
    }

    private static boolean isLogDirectory(String path) {
        return endsWithPathIgnoreCase(path, "logs") || endsWithPathIgnoreCase(path, "sentry/reports");
    }

    private static boolean endsWithPathIgnoreCase(String path, String suffix) {
        if (path.equalsIgnoreCase(suffix)) {
            return true;
        }
        int start = path.length() - suffix.length();
        return start > 0
                && path.charAt(start - 1) == '/'
                && path.regionMatches(true, start, suffix, 0, suffix.length());
    }

    private static String appendPath(String parent, String child) {
        return parent.isEmpty() ? child : parent + "/" + child;
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    private static boolean isWithinRoot(String root, String candidate) {
        if (root.equals(candidate)) {
            return true;
        }
        String prefix = root.endsWith(File.separator) ? root : root + File.separator;
        return candidate.startsWith(prefix);
    }

    private static final class DirectoryState {
        final File directory;
        final String relativePath;
        final boolean allFilesRelevant;

        DirectoryState(File directory, String relativePath, boolean allFilesRelevant) {
            this.directory = directory;
            this.relativePath = relativePath;
            this.allFilesRelevant = allFilesRelevant;
        }
    }
}
