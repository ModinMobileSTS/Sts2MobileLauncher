package com.godot.game;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class LocalSaveSnapshotSafetyTest {
	private LocalSaveSnapshotManager manager;
	private File account;
	private File save;
	private File snapshots;

	@Before public void setup() throws Exception {
		Context context = RuntimeEnvironment.getApplication();
		manager = new LocalSaveSnapshotManager(context);
		account = manager.getStatus().accountRoot;
		Files.createDirectories(account.toPath());
		save = new File(account, "progress.save");
		Files.write(save.toPath(), ("current-progress").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		snapshots = manager.getStatus().snapshotRoot;
	}

	@Test public void emptyAndTruncatedArchivesCannotDeleteSavesOrPruneRecoveryPoints() throws Exception {
		LocalSaveSnapshotManager.Snapshot good = manager.createManualSnapshot();
		byte[] valid = Files.readAllBytes(new File(snapshots, good.id).toPath());
		File empty = new File(snapshots, "empty.zip");
		File truncated = new File(snapshots, "truncated.zip");
		Files.write(empty.toPath(), new byte[0]);
		Files.write(truncated.toPath(), Arrays.copyOf(valid, valid.length - 22));
		for (File bad : List.of(empty, truncated)) {
			assertThrows(IOException.class, () -> manager.restoreSnapshot(bad.getName()));
			assertEquals("current-progress", new String(Files.readAllBytes(save.toPath()), java.nio.charset.StandardCharsets.UTF_8));
			assertEquals(List.of(good.id), manager.listSnapshots().stream().map(s -> s.id).toList());
		}
	}

	@Test public void missingDeclaredFilesAndTraversalAreRejectedBeforeRestore() throws Exception {
		File missing = writeArchive("missing.zip", "progress.save", false);
		File traversal = writeArchive("traversal.zip", "../outside.save", true);
		for (File bad : List.of(missing, traversal)) {
			assertThrows(IOException.class, () -> manager.restoreSnapshot(bad.getName()));
			assertEquals("current-progress", new String(Files.readAllBytes(save.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		}
		assertFalse(new File(snapshots, "outside.save").exists());
		assertTrue(manager.listSnapshots().isEmpty());
	}

	@Test public void crcCorruptionDoesNotReachCurrentAccount() throws Exception {
		File corrupt = writeArchive("crc.zip", "progress.save", true);
		byte[] bytes = Files.readAllBytes(corrupt.toPath());
		byte[] payload = "archived-progress".getBytes(StandardCharsets.UTF_8);
		int offset = -1;
		for (int i = 0; i <= bytes.length - payload.length; i++) {
			if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + payload.length), payload)) { offset = i; break; }
		}
		assertTrue(offset >= 0);
		bytes[offset] ^= 1;
		Files.write(corrupt.toPath(), bytes);
		assertThrows(IOException.class, () -> manager.restoreSnapshot(corrupt.getName()));
		assertEquals("current-progress", new String(Files.readAllBytes(save.toPath()), java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test public void restoreReplacesWholeTreeAndSafetyBackupContainsOriginalCurrentSaves() throws Exception {
		File nested = new File(account, "modded/1/current_run.save");
		Files.createDirectories(nested.getParentFile().toPath());
		Files.write(nested.toPath(), ("old-run").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.write(save.toPath(), ("old-progress").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		LocalSaveSnapshotManager.Snapshot old = manager.createManualSnapshot();
		Files.write(save.toPath(), ("current-progress").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.delete(nested.toPath());
		File extra = new File(account, "new.save");
		Files.write(extra.toPath(), ("new-file").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		manager.setRetentionLimit(1);
		assertEquals(old.id, manager.restoreSnapshot(old.id).id);
		assertEquals("old-progress", new String(Files.readAllBytes(save.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		assertEquals("old-run", new String(Files.readAllBytes(nested.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		assertFalse(extra.exists());
		LocalSaveSnapshotManager.Snapshot safety = manager.listSnapshots().get(0);
		assertEquals("before-restore", safety.reason);
		manager.restoreSnapshot(safety.id);
		assertEquals("current-progress", new String(Files.readAllBytes(save.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		assertEquals("new-file", new String(Files.readAllBytes(extra.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		assertFalse(nested.exists());
	}

	@Test public void failedCreationNeverPublishesPartialSnapshot() throws Exception {
		LocalSaveSnapshotManager.Snapshot good = manager.createManualSnapshot();
		// A reserved entry collides with the metadata after the output stream has been opened.
		Files.write(new File(account, "sts2_local_save_snapshot.json").toPath(), ("collision").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThrows(IOException.class, () -> manager.createManualSnapshot());
		assertEquals(List.of(good.id), Arrays.stream(snapshots.listFiles()).map(File::getName).toList());
	}

	@Test public void failedDirectoryCommitRollsBackOriginalSaves() throws Exception {
		File staging = new File(snapshots, "fault-staging") {
			@Override public boolean renameTo(File destination) { return false; }
		};
		Files.createDirectories(staging.toPath());
		File rollback = new File(snapshots, "fault-rollback");
		java.lang.reflect.Method commit = LocalSaveSnapshotManager.class.getDeclaredMethod(
			"installRestoredSaves", File.class, File.class, File.class);
		commit.setAccessible(true);
		java.lang.reflect.InvocationTargetException error = assertThrows(java.lang.reflect.InvocationTargetException.class,
			() -> commit.invoke(null, staging, account, rollback));
		assertTrue(error.getCause() instanceof IOException);
		assertEquals("current-progress", new String(Files.readAllBytes(save.toPath()), StandardCharsets.UTF_8));
		assertFalse(rollback.exists());
	}

	private File writeArchive(String name, String path, boolean includeSave) throws Exception {
		File file = new File(snapshots, name);
		JSONObject metadata = new JSONObject().put("schema", 1).put("type", "sts2_local_save_snapshot")
			.put("file_count", 1).put("paths", new JSONArray().put(path));
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
			out.putNextEntry(new ZipEntry("sts2_local_save_snapshot.json"));
			out.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			if (includeSave) {
				byte[] bytes = "archived-progress".getBytes(StandardCharsets.UTF_8);
				CRC32 crc = new CRC32(); crc.update(bytes);
				ZipEntry entry = new ZipEntry(path);
				entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCrc(crc.getValue());
				out.putNextEntry(entry); out.write(bytes); out.closeEntry();
			}
		}
		return file;
	}
}
