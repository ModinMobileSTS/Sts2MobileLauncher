package com.godot.game;

import android.content.Context;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ModInstallReplacementTest {
	private Context context;
	private ExtraSettingsRepository repository;
	private File mods;

	@Before public void setUp() throws Exception {
		context = RuntimeEnvironment.getApplication();
		for (String name : List.of("sts2_extra_settings", "sts2_version_manager", "sts2_mod_profiles")) {
			context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
		}
		for (String name : List.of("mods", "default", "instances", "payloads", "launcher")) {
			deleteTree(new File(context.getFilesDir(), name));
		}
		repository = new ExtraSettingsRepository(context);
		mods = repository.getModsRootDir();
	}

	@Test public void freshRepositoryCleansRecordedAuxiliaryAssetsAndPreviousDirectories() throws Exception {
		install("old/A.json", manifest("A", "1"), "old/A.dll", "main-v1",
			"old/Dependency.dll", "dependency-v1", "old/assets/icons/icon.bin", "synthetic-icon");
		repository = new ExtraSettingsRepository(context);

		ExtraSettingsRepository.PreparedModImport update = prepare(
			"new/A.json", manifest("A", "2"), "new/A.dll", "main-v2");
		assertTrue(update.fullPackage);
		assertTrue(update.isReplaceTrackedFiles());
		repository.commitPreparedModImport(update, true, false);

		assertBytes("new/A.dll", "main-v2");
		assertFalse(new File(mods, "old").exists());
		assertEquals("", update.getFileNotice());
		assertEquals(1, repository.listInstalledModManifests().size());
	}

	@Test public void generatedRuntimeAliasesAndOriginalPayloadsAreAllTracked() throws Exception {
		install("mod_manifest.json", new JSONObject().put("id", "AliasMod")
			.put("pck_name", "payload").put("version", "1").toString(),
			"payload.dll", "synthetic-dll", "payload.pck", "synthetic-pck", "assets/data.bin", "data");
		assertBytes("AliasMod.dll", "synthetic-dll");
		assertBytes("AliasMod.pck", "synthetic-pck");
		assertTrue(new File(mods, "AliasMod.json").isFile());
		assertFalse(new File(mods, "mod_manifest.json").exists());
		repository = new ExtraSettingsRepository(context);
		repository.deleteMod(entry("AliasMod"));
		for (String path : List.of("AliasMod.json", "AliasMod.dll", "AliasMod.pck", "payload.dll", "payload.pck", "assets/data.bin")) {
			assertFalse(path, new File(mods, path).exists());
		}
	}

	@Test public void uninstallDeletesRecordedAssetsButKeepsUnknownRuntimeData() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "main", "assets/owned.bin", "owned");
		write(new File(mods, "assets/runtime-save.bin"), "user-save");
		write(new File(mods, "runtime.log"), "user-log");
		repository = new ExtraSettingsRepository(context);
		assertEquals("", repository.deleteMod(entry("A")));
		assertFalse(new File(mods, "A.json").exists());
		assertFalse(new File(mods, "A.dll").exists());
		assertFalse(new File(mods, "assets/owned.bin").exists());
		assertBytes("assets/runtime-save.bin", "user-save");
		assertBytes("runtime.log", "user-log");
	}

	@Test public void updatePreservesDisabledGroupOrderAndUnrelatedSettings() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "main-v1");
		install("B.json", manifest("B", "1"), "B.dll", "other");
		repository.setModsEnabled(List.of(entry("A")), false);
		repository.moveModToGroup(entry("A"), "Favorites");
		repository.saveModOrder("Favorites", Arrays.asList("B", "A"), true);
		repository.saveModGroupOrder(Arrays.asList("Favorites", "Other"), true);
		repository.saveRuntimeModListOrder(Arrays.asList(entry("B"), entry("A")));
		JSONObject settings = repository.loadSettingsJson();
		settings.put("fixture_setting", "keep");
		repository.saveSettingsJson(settings);
		List<String> runtimeOrder = repository.loadRuntimeModListOrder(repository.loadSettingsJson());

		install("A.json", manifest("A", "2"), "A.dll", "main-v2");
		repository = new ExtraSettingsRepository(context);
		assertTrue(repository.isModDisabled(repository.loadSettingsJson(), entry("A")));
		assertEquals("Favorites", repository.loadModGroupAssignments().get("A"));
		assertEquals(Arrays.asList("B", "A"), repository.loadModOrder("Favorites"));
		assertEquals(Arrays.asList("Favorites", "Other"), repository.loadModGroupOrder());
		assertEquals(runtimeOrder, repository.loadRuntimeModListOrder(repository.loadSettingsJson()));
		assertEquals("keep", repository.loadSettingsJson().getString("fixture_setting"));
	}

	@Test public void modifiedStaleAuxiliaryStaysActiveAndReportsNoticeAcrossUpdates() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "main-v1", "Dependency.dll", "original");
		write(new File(mods, "Dependency.dll"), "user-modification");
		ExtraSettingsRepository.PreparedModImport update = prepare("A.json", manifest("A", "2"), "A.dll", "main-v2");
		repository.commitPreparedModImport(update, true, false);
		assertBytes("Dependency.dll", "user-modification");
		assertFalse(update.getFileNotice().isEmpty());

		repository = new ExtraSettingsRepository(context);
		ExtraSettingsRepository.PreparedModImport overlap = prepare("A.json", manifest("A", "3"),
			"A.dll", "main-v3", "Dependency.dll", "vendor-v3");
		try {
			assertPathConflict(overlap, "Dependency.dll");
		} finally {
			repository.discardPreparedModImport(overlap);
		}
	}

	@Test public void modifiedOverlapRequiresExplicitPathConfirmationAndRevalidation() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "main-v1", "Dependency.dll", "original");
		ExtraSettingsRepository.PreparedModImport update = prepare("A.json", manifest("A", "2"),
			"A.dll", "main-v2", "Dependency.dll", "vendor-v2");
		assertTrue(repository.findCurrentImportPathConflicts(update,
			repository.findCurrentImportConflicts(update)).isEmpty());
		// A file may change after preparation/review, so commit must review it again.
		write(new File(mods, "Dependency.dll"), "changed-after-review");
		assertPathConflict(update, "Dependency.dll");
		assertThrows(ExtraSettingsRepository.ModImportPathConflictException.class,
			() -> repository.commitPreparedModImport(update, true, false));
		assertBytes("A.dll", "main-v1");
		assertBytes("Dependency.dll", "changed-after-review");

		ExtraSettingsRepository.PreparedModImport confirmed = prepare("A.json", manifest("A", "2"),
			"A.dll", "main-v2", "Dependency.dll", "vendor-v2");
		repository.commitPreparedModImport(confirmed, true, true);
		assertBytes("Dependency.dll", "vendor-v2");
	}

	@Test public void multiModuleUpdateDoesNotDeleteOtherModuleOrAmbiguousSharedFiles() throws Exception {
		install("a/A.json", manifest("A", "1"), "a/A.dll", "a-v1", "a/assets/a.bin", "a-asset",
			"b/B.json", manifest("B", "1"), "b/B.dll", "b-v1", "b/assets/b.bin", "b-asset",
			"shared/data.bin", "shared-by-both");
		install("a/A.json", manifest("A", "2"), "a/A.dll", "a-v2");
		assertFalse(new File(mods, "a/assets/a.bin").exists());
		assertBytes("b/B.dll", "b-v1");
		assertBytes("b/assets/b.bin", "b-asset");
		assertBytes("shared/data.bin", "shared-by-both");
		repository.deleteMod(entry("A"));
		assertBytes("b/B.dll", "b-v1");
		assertBytes("shared/data.bin", "shared-by-both");
		repository.deleteMod(entry("B"));
		assertFalse(new File(mods, "shared/data.bin").exists());
	}

	@Test public void ambiguousSharedOverwriteRequiresConfirmation() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "a-v1",
			"B.json", manifest("B", "1"), "B.dll", "b-v1", "shared.bin", "shared");
		ExtraSettingsRepository.PreparedModImport update = prepare("A.json", manifest("A", "2"),
			"A.dll", "a-v2", "shared.bin", "new-owner-a");
		assertPathConflict(update, "shared.bin");
		repository.commitPreparedModImport(update, true, true);
		repository.deleteMod(entry("B"));
		assertBytes("shared.bin", "new-owner-a");
	}

	@Test public void explicitCrossModuleTakeoverSurvivesOriginalOwnerUninstall() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "a", "Dependency.dll", "from-a");
		ExtraSettingsRepository.PreparedModImport takeover = prepare("B.json", manifest("B", "1"),
			"B.dll", "b", "A.dll", "from-b-main", "Dependency.dll", "from-b");
		assertPathConflict(takeover, "Dependency.dll");
		assertPathConflict(takeover, "A.dll");
		repository.commitPreparedModImport(takeover, true, true);
		repository = new ExtraSettingsRepository(context);
		ExtraSettingsRepository.PreparedModImport reclaim = prepare("A.json", manifest("A", "2"), "A.dll", "reclaimed");
		try {
			assertPathConflict(reclaim, "A.dll");
		} finally {
			repository.discardPreparedModImport(reclaim);
		}
		repository.deleteMod(entry("A"));
		assertBytes("Dependency.dll", "from-b");
		assertBytes("A.dll", "from-b-main");
		repository.deleteMod(entry("B"));
		assertFalse(new File(mods, "Dependency.dll").exists());
		assertFalse(new File(mods, "A.dll").exists());
	}

	@Test public void standalonePatchRetainsOwnersForLaterFullCleanup() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "main", "Dependency.dll", "original",
			"assets/old.bin", "old-asset");
		File dll = File.createTempFile("standalone-", ".dll", context.getCacheDir());
		write(dll, "patched-dependency");
		ExtraSettingsRepository.PreparedModImport patch = repository.prepareDownloadedModImport(dll, "Dependency.dll");
		assertFalse(patch.fullPackage);
		assertFalse(patch.isReplaceTrackedFiles());
		repository.commitPreparedModImport(patch, true, true);
		assertBytes("Dependency.dll", "patched-dependency");
		assertBytes("assets/old.bin", "old-asset");
		assertTrue(new File(mods, "A.json").isFile());

		repository = new ExtraSettingsRepository(context);
		install("A.json", manifest("A", "2"), "A.dll", "main-v2");
		assertFalse(new File(mods, "Dependency.dll").exists());
		assertFalse(new File(mods, "assets/old.bin").exists());
	}

	@Test public void explicitPackagePatchKeepsAbsentFilesAndUninstallStillOwnsThem() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "main", "Dependency.dll", "dependency",
			"assets/old.bin", "asset");
		ExtraSettingsRepository.PreparedModImport patch = prepare("A.json", manifest("A", "2"), "A.dll", "patched-main");
		patch.setReplaceTrackedFiles(false);
		assertFalse(patch.isReplaceTrackedFiles());
		repository.commitPreparedModImport(patch, true, false);
		assertBytes("Dependency.dll", "dependency");
		assertBytes("assets/old.bin", "asset");
		repository = new ExtraSettingsRepository(context);
		repository.deleteMod(entry("A"));
		assertFalse(new File(mods, "Dependency.dll").exists());
		assertFalse(new File(mods, "assets/old.bin").exists());
	}

	@Test public void legacyUpdateAndUninstallOnlyRemoveKnownMainFilesAndWarn() throws Exception {
		write(new File(mods, "legacy/A.json"), manifest("A", "1"));
		write(new File(mods, "legacy/A.dll"), "legacy-main");
		write(new File(mods, "legacy/Dependency.dll"), "not-inferable");
		write(new File(mods, "legacy/assets/user.bin"), "unknown");
		ExtraSettingsRepository.PreparedModImport update = prepare("current/A.json", manifest("A", "2"), "current/A.dll", "new");
		repository.commitPreparedModImport(update, true, false);
		assertFalse(update.getFileNotice().isEmpty());
		assertFalse(new File(mods, "legacy/A.json").exists());
		assertFalse(new File(mods, "legacy/A.dll").exists());
		assertBytes("legacy/Dependency.dll", "not-inferable");
		assertBytes("legacy/assets/user.bin", "unknown");

		write(new File(mods, "loose/B.json"), manifest("B", "1"));
		write(new File(mods, "loose/B.dll"), "legacy-b");
		write(new File(mods, "loose/runtime.bin"), "keep");
		assertFalse(repository.deleteMod(entry("B")).isEmpty());
		assertFalse(new File(mods, "loose/B.json").exists());
		assertFalse(new File(mods, "loose/B.dll").exists());
		assertBytes("loose/runtime.bin", "keep");
	}

	@Test public void isolatedAndGlobalReceiptsCannotCrossDeleteAndPreparedScopeCannotChange() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "global", "assets/data.bin", "global-asset");
		File globalMods = mods;
		ExtraSettingsRepository.PreparedModImport globalPrepared = prepare("A.json", manifest("A", "2"), "A.dll", "wrong-scope");
		LaunchProfileManager profiles = new LaunchProfileManager(context);
		createPayload(profiles);
		LaunchProfileManager.LaunchProfile isolated = profiles.createProfile("mod-install-fixture", "Isolated",
			LaunchProfileManager.SAVE_MODE_ISOLATED, LaunchProfileManager.MODS_MODE_ISOLATED, "", "", LaunchProfileManager.COMPAT_SELECTION_MANUAL, true);
		mods = repository.getModsRootDir();
		assertNotEquals(globalMods.getCanonicalPath(), mods.getCanonicalPath());
		assertThrows(IOException.class, () -> repository.commitPreparedModImport(globalPrepared, true, true));
		assertEquals("global", read(new File(globalMods, "A.dll")));
		assertFalse(new File(mods, "A.dll").exists());

		install("A.json", manifest("A", "1"), "A.dll", "isolated", "assets/data.bin", "isolated-asset");
		repository.deleteMod(entry("A"));
		assertFalse(new File(mods, "A.dll").exists());
		assertEquals("global-asset", read(new File(globalMods, "assets/data.bin")));
		assertEquals("global", read(new File(globalMods, "A.dll")));
		profiles.selectProfile(isolated.id);
		assertTrue(repository.listInstalledModManifests().isEmpty());
	}

	@Test public void stagedAndDestinationSymlinksFailClosedWithoutOutsideWrites() throws Exception {
		File outside = Files.createTempDirectory(context.getCacheDir().toPath(), "outside-mods-").toFile();
		write(new File(outside, "sentinel.bin"), "private");
		ExtraSettingsRepository.PreparedModImport staged = prepare("A.json", manifest("A", "1"), "A.dll", "main");
		Files.createSymbolicLink(new File(staged.stagingRoot, "escape").toPath(), outside.toPath());
		assertThrows(IOException.class, () -> repository.commitPreparedModImport(staged, true, true));
		assertEquals("private", read(new File(outside, "sentinel.bin")));
		assertFalse(new File(mods, "A.dll").exists());

		Files.createDirectories(mods.toPath());
		Files.createSymbolicLink(new File(mods, "escape").toPath(), outside.toPath());
		assertThrows(IOException.class, () -> {
			ExtraSettingsRepository.PreparedModImport incoming = prepare("A.json", manifest("A", "1"),
				"A.dll", "main", "escape/sentinel.bin", "overwrite");
			repository.commitPreparedModImport(incoming, true, true);
		});
		assertEquals("private", read(new File(outside, "sentinel.bin")));
	}


	@Test public void modifiedUninstallReportsPersistentPreservationNotice() throws Exception {
		install("A.json", manifest("A", "1"), "A.dll", "main", "assets/modified.bin", "original");
		write(new File(mods, "assets/modified.bin"), "edited");
		assertFalse(repository.deleteMod(entry("A")).isEmpty());
		assertFalse(new File(mods, "A.json").exists());
		assertFalse(new File(mods, "assets/modified.bin").exists());
	}

	@Test public void directoryFileCollisionCannotRecursivelyDeleteUnknownData() throws Exception {
		write(new File(mods, "collision/user.bin"), "keep");
		assertThrows(IOException.class, () -> {
			ExtraSettingsRepository.PreparedModImport incoming = prepare("A.json", manifest("A", "1"), "A.dll", "main",
				"collision", "not-a-directory");
			repository.commitPreparedModImport(incoming, true, true);
		});
		assertBytes("collision/user.bin", "keep");
	}

	@Test public void workshopUpdateReplacesWholeItemIncludingUntrackedFiles() throws Exception {
		ExtraSettingsRepository.WorkshopModImportResult first = workshop("A.json", manifest("A", "1"),
			"A.dll", "workshop-v1", "assets/old.bin", "old");
		repository.finalizeWorkshopModImport(first);
		write(new File(first.installRoot, "runtime-generated.bin"), "whole-item-owned-by-user-choice");
		repository = new ExtraSettingsRepository(context);
		ExtraSettingsRepository.WorkshopModImportResult update = workshop("A.json", manifest("A", "2"), "A.dll", "workshop-v2");
		boolean finalized = false;
		try {
			assertEquals(first.installRoot.getCanonicalPath(), update.installRoot.getCanonicalPath());
			assertEquals("workshop-v2", read(new File(update.installRoot, "A.dll")));
			assertFalse(new File(update.installRoot, "assets/old.bin").exists());
			assertFalse(new File(update.installRoot, "runtime-generated.bin").exists());
			repository.finalizeWorkshopModImport(update);
			finalized = true;
		} finally {
			if (!finalized) {
				repository.rollbackWorkshopModImport(update);
			}
		}
		assertEquals("workshop-v2", read(new File(first.installRoot, "A.dll")));
	}

	@Test public void workshopRollbackRestoresWholeItemAndSameIdFilesOutsideItem() throws Exception {
		ExtraSettingsRepository.WorkshopModImportResult first = workshop("Old.json", manifest("Old", "1"), "Old.dll", "old-item");
		repository.finalizeWorkshopModImport(first);
		write(new File(first.installRoot, "runtime.bin"), "runtime-before");
		install("outside/A.json", manifest("A", "1"), "outside/A.dll", "outside-main", "outside/assets/data.bin", "outside-asset");
		ExtraSettingsRepository.WorkshopModImportResult replacement = workshop("A.json", manifest("A", "2"), "A.dll", "incoming");
		try {
			assertFalse(new File(mods, "outside/A.dll").exists());
			assertFalse(new File(mods, "outside/assets/data.bin").exists());
			assertFalse(new File(first.installRoot, "Old.dll").exists());
			assertEquals("incoming", read(new File(replacement.installRoot, "A.dll")));
		} finally {
			repository.rollbackWorkshopModImport(replacement);
		}
		repository.rollbackWorkshopModImport(replacement);
		assertEquals("old-item", read(new File(first.installRoot, "Old.dll")));
		assertEquals("runtime-before", read(new File(first.installRoot, "runtime.bin")));
		assertFalse(new File(first.installRoot, "A.dll").exists());
		assertBytes("outside/A.dll", "outside-main");
		assertBytes("outside/assets/data.bin", "outside-asset");
		// Rollback must restore the receipt as well, not just visible bytes.
		repository = new ExtraSettingsRepository(context);
		repository.deleteMod(entry("A"));
		assertFalse(new File(mods, "outside/assets/data.bin").exists());
		assertEquals("old-item", read(new File(first.installRoot, "Old.dll")));
	}

	private void install(String... entries) throws Exception {
		repository.commitPreparedModImport(prepare(entries), true, false);
	}

	private ExtraSettingsRepository.PreparedModImport prepare(String... entries) throws Exception {
		File zip = File.createTempFile("mod-replacement-", ".zip", context.getCacheDir());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
			for (int i = 0; i < entries.length; i += 2) {
				out.putNextEntry(new ZipEntry(entries[i]));
				out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		return repository.prepareDownloadedModImport(zip, "fixture.zip");
	}

	private ExtraSettingsRepository.WorkshopModImportResult workshop(String... entries) throws Exception {
		return repository.commitPreparedWorkshopModImport(prepare(entries), "Workshop", "123456", true);
	}

	private ExtraSettingsRepository.ModEntry entry(String id) {
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			if (id.equals(entry.modId)) {
				return entry;
			}
		}
		throw new AssertionError("Missing installed module: " + id);
	}

	private void assertPathConflict(ExtraSettingsRepository.PreparedModImport prepared, String path) throws Exception {
		for (ExtraSettingsRepository.ModImportPathConflict conflict : repository.findCurrentImportPathConflicts(
			prepared, repository.findCurrentImportConflicts(prepared))) {
			if (path.equals(conflict.relativePath)) {
				return;
			}
		}
		fail("Expected path conflict: " + path);
	}

	private void assertBytes(String relativePath, String expected) throws Exception {
		assertEquals(relativePath, expected, read(new File(mods, relativePath)));
	}

	private static String manifest(String id, String version) throws Exception {
		return new JSONObject().put("id", id).put("pck_name", id).put("version", version).toString();
	}

	private static void createPayload(LaunchProfileManager profiles) throws Exception {
		File game = profiles.getPayloadGameDir("mod-install-fixture");
		write(new File(game, "SlayTheSpire2.pck"), "synthetic-pck");
		File data = new File(game, "data_sts2_windows_x86_64");
		write(new File(data, "sts2.dll"), "synthetic-dll");
		write(new File(data, "sts2.deps.json"), "{}");
		write(new File(data, "sts2.runtimeconfig.json"), "{}");
		write(new File(game, ".payload_manifest.json"), new JSONObject().put("identity",
			new JSONObject().put("release_info", new JSONObject().put("version", "fixture"))).toString());
	}

	private static void write(File file, String content) throws Exception {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static void deleteTree(File file) throws Exception {
		if (Files.isSymbolicLink(file.toPath())) {
			Files.delete(file.toPath());
			return;
		}
		if (!file.exists()) {
			return;
		}
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteTree(child);
			}
		}
		Files.delete(file.toPath());
	}
}
