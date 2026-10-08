package com.godot.game;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogFileScannerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void findsLogFilesAndExtensionlessFilesBelowKnownLogDirectories() throws Exception {
        File root = temporary.newFolder("root");
        write(root, "nested/ordinary.log");
        write(root, "nested/ordinary.txt");
        write(root, "logs/without-extension");
        write(root, "deep/sentry/reports/report.json");
        write(root, "deep/sentry/reports/archive.bin");

        List<LogFileScanner.Entry> entries = scan(root);
        Set<String> paths = new HashSet<>();
        for (LogFileScanner.Entry entry : entries) {
            paths.add(entry.relativePath);
        }

        assertEquals(4, paths.size());
        assertTrue(paths.contains("nested/ordinary.log"));
        assertTrue(paths.contains("logs/without-extension"));
        assertTrue(paths.contains("deep/sentry/reports/report.json"));
        assertTrue(paths.contains("deep/sentry/reports/archive.bin"));
        assertFalse(paths.contains("nested/ordinary.txt"));
    }

    @Test public void deliversFirstLogBeforeWalkingTheRemainingTree() throws Exception {
        File root = temporary.newFolder("progress");
        File first = write(root, "first.log");
        File later = write(root, "large/later.log");
        List<LogFileScanner.Entry> delivered = new ArrayList<>();
        File remainingDirectory = new File(later.getParent()) {
            @Override public File[] listFiles() {
                // A large unrelated subtree must not hold an already found file in a batch.
                assertEquals("first.log", delivered.get(0).relativePath);
                return super.listFiles();
            }
        };
        File orderedRoot = new File(root.getPath()) {
            @Override public File[] listFiles() {
                return new File[] {first, remainingDirectory};
            }
        };
        LogFileScanner.scan(delivered::addAll, new LogFileScanner.Root(orderedRoot, "archive", "display"));

        Set<String> paths = new HashSet<>();
        for (LogFileScanner.Entry entry : delivered) {
            paths.add(entry.relativePath);
        }
        assertEquals(new HashSet<>(java.util.Arrays.asList("first.log", "large/later.log")), paths);
    }

    @Test public void doesNotFollowDirectoryLinksOutsideTheRootOrIntoACycle() throws Exception {
        File root = temporary.newFolder("root");
        write(root, "logs/current.log");
        File outside = temporary.newFolder("outside");
        write(outside, "outside.log");
        Files.createSymbolicLink(new File(root, "outside").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(root, "cycle").toPath(), root.toPath());

        List<LogFileScanner.Entry> entries = scan(root);

        assertEquals(1, entries.size());
        assertEquals("logs/current.log", entries.get(0).relativePath);
    }

    @Test public void prioritizesKnownLogLocationsWithoutDroppingLogsElsewhere() throws Exception {
        File root = temporary.newFolder("locations");
        File current = write(root, "logs/godot.log");
        write(root, "mods/custom/extra.LOG");
        write(root, "payloads/diagnostics.log");
        List<LogFileScanner.Entry> delivered = new ArrayList<>();
        File payloads = new File(root, "payloads") {
            @Override public File[] listFiles() {
                assertEquals(current, delivered.get(0).file);
                return super.listFiles();
            }
        };
        File orderedRoot = new File(root.getPath()) {
            @Override public File[] listFiles() {
                return new File[] {new File(root, "logs"), new File(root, "mods"), payloads};
            }
        };
        LogFileScanner.scan(delivered::addAll, new LogFileScanner.Root(orderedRoot, "archive", "display"));
        Set<String> paths = new HashSet<>();
        for (LogFileScanner.Entry entry : delivered) {
            paths.add(entry.relativePath);
        }
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                "logs/godot.log", "mods/custom/extra.LOG", "payloads/diagnostics.log")), paths);
    }

    @Test public void interruptAfterFirstResultStopsRemainingDirectoriesAndRoots() throws Exception {
        File root = temporary.newFolder("cancel");
        File first = write(root, "first.log");
        File remaining = temporary.newFolder("remaining");
        File remainingDirectory = new File(remaining.getPath()) {
            @Override public File[] listFiles() {
                throw new AssertionError("Interrupted scan walked the next root");
            }
        };
        List<LogFileScanner.Entry> delivered = new ArrayList<>();
        try {
            LogFileScanner.scan(batch -> {
                delivered.addAll(batch);
                Thread.currentThread().interrupt();
            }, new LogFileScanner.Root(root, "archive", "display"),
                    new LogFileScanner.Root(remainingDirectory, "external", "External"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, delivered.size());
            assertEquals(first, delivered.get(0).file);
        } finally {
            Thread.interrupted();
        }
    }

    private List<LogFileScanner.Entry> scan(File root) {
        List<LogFileScanner.Entry> entries = new ArrayList<>();
        LogFileScanner.scan(entries::addAll, new LogFileScanner.Root(root, "archive", "display"));
        return entries;
    }

    private File write(File root, String relativePath) throws Exception {
        File file = new File(root, relativePath);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), relativePath.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.isFile());
        return file;
    }
}
