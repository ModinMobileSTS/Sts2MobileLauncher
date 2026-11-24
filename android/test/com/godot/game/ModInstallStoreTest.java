package com.godot.game;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = AndroidLinuxTestShadow.class)
public class ModInstallStoreTest {
	private File files;
	private File mods;
	private File stagingParent;
	private ModInstallStore store;

	@Before public void setUp() throws Exception {
		File cache = RuntimeEnvironment.getApplication().getCacheDir();
		files = Files.createTempDirectory(cache.toPath(), "mod-store-files-").toFile();
		mods = new File(files, "mods");
		stagingParent = Files.createTempDirectory(cache.toPath(), "mod-store-staging-").toFile();
		store = new ModInstallStore(files, mods);
	}

	@Test public void closeWithoutCommitRestoresFilesAndReceipt() throws Exception {
		ModInstallStore.Module a = module("A", "A.json");
		install(stage("A.json", "v1", "A.dll", "main-v1", "assets/old.bin", "old"), List.of(a));
		try (ModInstallStore.Transaction ignored = store.beginInstall(
			stage("A.json", "v2", "A.dll", "main-v2", "assets/new.bin", "new"), "",
			List.of(a), List.of(a), true, false, false)) {
			assertEquals("main-v2", read(new File(mods, "A.dll")));
			assertFalse(new File(mods, "assets/old.bin").exists());
			assertTrue(new File(mods, "assets/new.bin").isFile());
		}
		assertEquals("main-v1", read(new File(mods, "A.dll")));
		assertEquals("old", read(new File(mods, "assets/old.bin")));
		assertFalse(new File(mods, "assets/new.bin").exists());

		ModInstallStore reopened = new ModInstallStore(files, mods);
		reopened.recover();
		reopened.recover();
		try (ModInstallStore.Transaction removal = reopened.beginRemoval(List.of(a), "")) {
			removal.commit();
		}
		assertFalse(new File(mods, "assets/old.bin").exists());
	}

	@Test public void explicitRollbackIsIdempotentAndReleasesLock() throws Exception {
		ModInstallStore.Module a = module("A", "A.json");
		install(stage("A.json", "v1", "A.dll", "before"), List.of(a));
		try (ModInstallStore.Transaction transaction = store.beginInstall(stage("A.json", "v2", "A.dll", "after"),
			"", List.of(a), List.of(a), true, false, false)) {
			transaction.rollback();
			transaction.rollback();
		}
		assertEquals("before", read(new File(mods, "A.dll")));
		try (ModInstallStore.Transaction transaction = new ModInstallStore(files, mods).beginRemoval(List.of(a), "")) {
			transaction.commit();
		}
		assertFalse(new File(mods, "A.dll").exists());
	}

	@Test public void removalCloseRestoresDeletedFilesAndOwnership() throws Exception {
		ModInstallStore.Module a = module("A", "A.json");
		install(stage("A.json", "v1", "A.dll", "main", "assets/data.bin", "asset"), List.of(a));
		try (ModInstallStore.Transaction ignored = store.beginRemoval(List.of(a), "")) {
			assertFalse(new File(mods, "A.dll").exists());
			assertFalse(new File(mods, "assets/data.bin").exists());
		}
		assertEquals("main", read(new File(mods, "A.dll")));
		assertEquals("asset", read(new File(mods, "assets/data.bin")));
		try (ModInstallStore.Transaction removal = new ModInstallStore(files, mods).beginRemoval(List.of(a), "")) {
			removal.commit();
		}
		assertFalse(new File(mods, "assets/data.bin").exists());
	}

	@Test public void modifiedRemovalCreatesPersistentBackupOutsideModsAndPreservesUnknownFiles() throws Exception {
		ModInstallStore.Module a = module("A", "A.json");
		install(stage("A.json", "v1", "A.dll", "main", "assets/data.bin", "original"), List.of(a));
		write(new File(mods, "assets/data.bin"), "user-edited-owned-file");
		write(new File(mods, "assets/generated.bin"), "unknown-runtime-file");
		File preserved;
		try (ModInstallStore.Transaction removal = store.beginRemoval(List.of(a), "")) {
			preserved = removal.preservedDirectory;
			assertNotNull(preserved);
			assertFalse(preserved.getCanonicalPath().startsWith(mods.getCanonicalPath() + File.separator));
			assertTrue(removal.preservedPaths.contains("assets/data.bin"));
			removal.commit();
		}
		assertFalse(new File(mods, "assets/data.bin").exists());
		assertEquals("unknown-runtime-file", read(new File(mods, "assets/generated.bin")));
		assertTrue(preserved.isDirectory());
		assertTrue(contents(preserved).contains("user-edited-owned-file"));
		ModInstallStore reopened = new ModInstallStore(files, mods);
		reopened.recover();
		reopened.recover();
		assertTrue(contents(preserved).contains("user-edited-owned-file"));
	}

	@Test public void nestedManifestNamespaceUsesClosestOwnerAndLeavesAmbiguousRootShared() throws Exception {
		ModInstallStore.Module outer = module("Outer", "pack/Outer.json");
		ModInstallStore.Module inner = module("Inner", "pack/nested/Inner.json");
		install(stage("pack/Outer.json", "outer-manifest", "pack/Outer.dll", "outer-main",
			"pack/outer.bin", "outer-asset", "pack/nested/Inner.json", "inner-manifest",
			"pack/nested/Inner.dll", "inner-main", "pack/nested/asset.bin", "inner-asset",
			"shared.bin", "ambiguous"), List.of(outer, inner));
		try (ModInstallStore.Transaction removal = store.beginRemoval(List.of(outer), "")) {
			removal.commit();
		}
		assertFalse(new File(mods, "pack/outer.bin").exists());
		assertEquals("inner-asset", read(new File(mods, "pack/nested/asset.bin")));
		assertEquals("ambiguous", read(new File(mods, "shared.bin")));
		try (ModInstallStore.Transaction removal = store.beginRemoval(List.of(inner), "")) {
			removal.commit();
		}
		assertFalse(new File(mods, "pack/nested/asset.bin").exists());
		assertFalse(new File(mods, "shared.bin").exists());
	}

	@Test public void beginRevalidatesModifiedTrackedFileEvenAfterCleanReview() throws Exception {
		ModInstallStore.Module a = module("A", "A.json");
		install(stage("A.json", "v1", "A.dll", "before"), List.of(a));
		File incoming = stage("A.json", "v2", "A.dll", "after");
		assertTrue(store.review(incoming, "", List.of(a), List.of(a), true, false).conflictPaths.isEmpty());
		write(new File(mods, "A.dll"), "edited-after-review");
		ModInstallStore.OverwriteException failure = assertThrows(ModInstallStore.OverwriteException.class,
			() -> store.beginInstall(incoming, "", List.of(a), List.of(a), true, false, false));
		assertTrue(failure.paths.contains("A.dll"));
		assertEquals("edited-after-review", read(new File(mods, "A.dll")));
		assertEquals("v1", new JSONObject(read(new File(mods, "A.json"))).getString("fixture"));
	}

	@Test public void wholeItemCloseRollbackRestoresOutsideItemFilesAndReceiptTogether() throws Exception {
		String item = "Workshop/public/123456";
		ModInstallStore.Module oldItem = module("Old", item + "/Old.json");
		try (ModInstallStore.Transaction first = store.beginInstall(stage("Old.json", "old-manifest", "Old.dll", "old-main"),
			item, List.of(oldItem), Collections.emptyList(), true, false, true)) {
			first.commit();
		}
		write(new File(mods, item + "/unknown.bin"), "whole-item-runtime");
		ModInstallStore.Module outside = module("A", "outside/A.json");
		install(stage("outside/A.json", "outside-manifest", "outside/A.dll", "outside-main", "outside/assets.bin", "outside-asset"),
			List.of(outside));
		ModInstallStore.Module incoming = module("A", item + "/A.json");
		try (ModInstallStore.Transaction ignored = store.beginInstall(stage("A.json", "incoming-manifest", "A.dll", "incoming-main"),
			item, List.of(incoming), List.of(oldItem, outside), true, false, true)) {
			assertFalse(new File(mods, "outside/assets.bin").exists());
			assertFalse(new File(mods, item + "/unknown.bin").exists());
			assertEquals("incoming-main", read(new File(mods, item + "/A.dll")));
		}
		assertEquals("outside-main", read(new File(mods, "outside/A.dll")));
		assertEquals("outside-asset", read(new File(mods, "outside/assets.bin")));
		assertEquals("old-main", read(new File(mods, item + "/Old.dll")));
		assertEquals("whole-item-runtime", read(new File(mods, item + "/unknown.bin")));
		assertFalse(new File(mods, item + "/A.dll").exists());
		try (ModInstallStore.Transaction removal = new ModInstallStore(files, mods).beginRemoval(List.of(outside), "")) {
			removal.commit();
		}
		assertFalse(new File(mods, "outside/assets.bin").exists());
	}

	@Test public void wholeItemRemovalCloseRestoresUnknownContentsAndCommitRemovesOnlyItem() throws Exception {
		String item = "Workshop/public/123456";
		ModInstallStore.Module a = module("A", item + "/A.json");
		try (ModInstallStore.Transaction install = store.beginInstall(stage("A.json", "manifest", "A.dll", "main"),
			item, List.of(a), Collections.emptyList(), true, false, true)) {
			install.commit();
		}
		write(new File(mods, item + "/unknown.bin"), "unknown");
		write(new File(mods, "Workshop/public/other/unknown.bin"), "other-item");
		try (ModInstallStore.Transaction ignored = store.beginRemoval(List.of(a), item)) {
			assertFalse(new File(mods, item).exists());
		}
		assertEquals("unknown", read(new File(mods, item + "/unknown.bin")));
		try (ModInstallStore.Transaction removal = store.beginRemoval(List.of(a), item)) {
			removal.commit();
		}
		assertFalse(new File(mods, item).exists());
		assertEquals("other-item", read(new File(mods, "Workshop/public/other/unknown.bin")));
	}

	@Test public void symlinkedModsAncestorAndRegistryAreRejected() throws Exception {
		File external = Files.createTempDirectory(stagingParent.toPath(), "external-").toFile();
		write(new File(external, "sentinel"), "outside");
		File ancestor = new File(files, "linked");
		Files.createSymbolicLink(ancestor.toPath(), external.toPath());
		assertThrows(IOException.class, () -> new ModInstallStore(files, new File(ancestor, "mods")));
		assertEquals("outside", read(new File(external, "sentinel")));
		assertFalse(new File(external, "mods").exists());

		File otherFiles = Files.createTempDirectory(stagingParent.toPath(), "registry-files-").toFile();
		Files.createSymbolicLink(new File(otherFiles, "launcher").toPath(), external.toPath());
		assertThrows(IOException.class, () -> {
			ModInstallStore unsafe = new ModInstallStore(otherFiles, new File(otherFiles, "mods"));
			unsafe.recover();
		});
		assertFalse(new File(external, "mod-installs").exists());
		assertEquals("outside", read(new File(external, "sentinel")));
	}

	@Test public void stagedSymlinksAndOutOfRootModsCannotBeAdopted() throws Exception {
		File external = Files.createTempDirectory(stagingParent.toPath(), "external-").toFile();
		write(new File(external, "private.bin"), "outside");
		assertThrows(IOException.class, () -> new ModInstallStore(files, external));
		File staged = stage("A.json", "manifest", "A.dll", "main");
		Files.createSymbolicLink(new File(staged, "linked.bin").toPath(), new File(external, "private.bin").toPath());
		ModInstallStore.Module a = module("A", "A.json");
		assertThrows(IOException.class, () -> store.beginInstall(staged, "", List.of(a), Collections.emptyList(), true, true, false));
		assertFalse(new File(mods, "A.dll").exists());
		assertEquals("outside", read(new File(external, "private.bin")));
	}

	@Test public void cleanHandoffNeverOpensAgainstWriterOrReplaysJournalOnCaller() throws Exception {
		ModInstallStore.Module a = module("A", "A.json");
		install(stage("A.json", "v1", "A.dll", "old"), List.of(a));
		File[] registries = new File(files, "launcher/mod-installs").listFiles();
		assertNotNull(registries);
		assertEquals(1, registries.length);
		File saved = new File(stagingParent, "interrupted"), journal = new File(registries[0], "transaction");
		try (ModInstallStore.Transaction tx = store.beginInstall(stage("A.json", "v2", "A.dll", "new"), "",
			List.of(a), List.of(a), true, false, false)) {
			assertThrows(ModInstallStore.BusyException.class, () -> store.withCleanRoot(() -> fail("writer owns root")));
			// Preserve an actual READY journal and the matching post-publication live files.
			copyTree(journal, saved);
			tx.commit();
		}
		copyTree(saved, journal);
		List<?> uncommitted = ModManifestCache.get(mods, List::of);
		assertFalse(store.withCleanRoot(() -> fail("pending rollback must return to the worker")));
		assertEquals("READY", read(new File(journal, "phase")));
		assertEquals("new", read(new File(mods, "A.dll")));
		store.recover();
		assertNotSame(uncommitted, ModManifestCache.get(mods, List::of));
		assertEquals("old", read(new File(mods, "A.dll")));
		int[] calls = {0};
		assertTrue(store.withCleanRoot(() -> {
			assertThrows(ModInstallStore.BusyException.class, store::recover);
			calls[0]++;
		}));
		assertEquals(1, calls[0]);
		write(new File(registries[0], "index.json"), "broken receipt");
		assertThrows(IOException.class, () -> store.withCleanRoot(() -> fail("corrupt receipt admitted")));
	}

	private static void copyTree(File source, File destination) throws IOException {
		Files.copy(source.toPath(), destination.toPath());
		if (!source.isDirectory()) return;
		File[] children = source.listFiles();
		assertNotNull(children);
		for (File child : children) copyTree(child, new File(destination, child.getName()));
	}

	private void install(File staging, List<ModInstallStore.Module> modules) throws Exception {
		try (ModInstallStore.Transaction transaction = store.beginInstall(staging, "", modules, Collections.emptyList(), true, false, false)) {
			transaction.commit();
		}
	}

	private static ModInstallStore.Module module(String id, String manifest) {
		String parent = manifest.contains("/") ? manifest.substring(0, manifest.lastIndexOf('/') + 1) : "";
		return new ModInstallStore.Module(id, manifest, "fixture", List.of(manifest, parent + id + ".dll", parent + id + ".pck"));
	}

	private File stage(String... entries) throws Exception {
		File root = Files.createTempDirectory(stagingParent.toPath(), "incoming-").toFile();
		for (int i = 0; i < entries.length; i += 2) {
			String value = entries[i].endsWith(".json")
				? new JSONObject().put("fixture", entries[i + 1]).toString() : entries[i + 1];
			write(new File(root, entries[i]), value);
		}
		return root;
	}

	private static List<String> contents(File directory) throws Exception {
		List<String> values = new ArrayList<>();
		File[] children = directory.listFiles();
		assertNotNull(children);
		for (File child : children) {
			assertFalse(Files.isSymbolicLink(child.toPath()));
			if (child.isDirectory()) {
				values.addAll(contents(child));
			} else {
				values.add(read(child));
			}
		}
		return values;
	}

	private static void write(File file, String value) throws Exception {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}
}
