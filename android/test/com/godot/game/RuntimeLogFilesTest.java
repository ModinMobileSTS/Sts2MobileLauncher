package com.godot.game;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeLogFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void newestLiveFileIsChosenPerNameWithoutArchivedOrOtherLogs() throws Exception {
        File internal = temporary.newFolder("internal");
        File external = temporary.newFolder("external");
        File oldGodot = log(internal, "instances/older/logs/godot.log", 10000);
        File newestGodot = log(internal, "instances/newer/logs/godot.log", 20000);
        File newestSts2 = log(external, "logs/sts2.log", 30000);
        log(internal, "logs/sts2.log", 15000);
        log(external, "logs/godot.2026-10-07.log", 90000);
        log(internal, "logs/android-launch.log", 95000);
        log(internal, "logs/sts22026-10-07.log", 98000);

        assertEquals(Arrays.asList(newestGodot, newestSts2), RuntimeLogFiles.latest(internal, external));
        assertTrue(oldGodot.isFile());
    }

    @Test public void missingOneSourceDoesNotReplaceItWithUnrelatedFile() throws Exception {
        File root = temporary.newFolder("root");
        File current = log(root, "logs/godot.log", 12000);
        log(root, "logs/sts2-old.log", 14000);
        assertEquals(Arrays.asList(current), RuntimeLogFiles.latest(root, null));
        assertEquals(Arrays.asList(current), RuntimeLogFiles.selectLatest(Arrays.asList(current, new File(root, "missing/sts2.log"))));
    }

    @Test public void symlinkCannotAddAnOutsideNewerLogOrDirectoryCycle() throws Exception {
        File root = temporary.newFolder("root");
        File current = log(root, "logs/godot.log", 12000);
        File outside = temporary.newFolder("outside");
        log(outside, "godot.log", 50000);
        Files.createSymbolicLink(new File(root, "outside").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(root, "cycle").toPath(), root.toPath());
        assertEquals(Arrays.asList(current), RuntimeLogFiles.latest(root, null));
    }

    private File log(File root, String relative, long modified) throws Exception {
        File file = new File(root, relative);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), relative.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(modified));
        return file;
    }
}
