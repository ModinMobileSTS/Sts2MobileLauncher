package com.godot.game;

import android.content.Context;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = AndroidLinuxTestShadow.class)
public class ModManifestMaintenanceTest {
	private Context context;
	private ExtraSettingsRepository repository;
	private File mods;

	@Before public void setUp() throws Exception {
		context = RuntimeEnvironment.getApplication();
		repository = new ExtraSettingsRepository(context);
		repository.ensureAppDirectories();
		mods = repository.getModsRootDir();
	}

	@Test public void cleanPageReadinessLeavesMaintenanceUntilLaunchAndDoesNotInvalidateDisplay() throws Exception {
		File backup = new File(mods, ".hidden/group/.123.backup-interrupted");
		File incoming = new File(mods, ".hidden/group/.123.incoming-interrupted");
		write(new File(backup, "mod_manifest.json"), "{\"id\":\"Restored\",\"pck_name\":\"payload\"}");
		write(new File(backup, "payload.dll"), "restored bytes");
		manifest(new File(incoming, "Partial.json"), "Partial");
		File manual = new File(mods, "manual");
		manifest(new File(manual, "other.json"), "Manual");
		List<ExtraSettingsRepository.ModEntry> before = repository.listInstalledModManifestsForDisplay();
		assertEquals(List.of("Manual"), ids(before));
		assertTrue(repository.listInstalledModManifestsUnder(backup).isEmpty());
		repository.ensureAppDirectories();
		assertTrue(backup.isDirectory());
		assertTrue(incoming.isDirectory());
		assertTrue(new File(manual, "other.json").isFile());
		assertSame(before, new ExtraSettingsRepository(context).listInstalledModManifestsForDisplay());
		assertEquals(mods, repository.prepareModsForLaunch());
		assertFalse(backup.exists());
		assertFalse(incoming.exists());
		assertTrue(new File(manual, "Manual.json").isFile());
		File restored = new File(backup.getParentFile(), "123");
		assertTrue(new File(restored, "Restored.json").isFile());
		assertArrayEquals("restored bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(new File(restored, "Restored.dll").toPath()));
		assertFalse(new File(restored, "mod_manifest.json").exists());
		assertEquals(Set.of("Manual", "Restored"), new HashSet<>(ids(repository.listInstalledModManifestsForDisplay())));
	}

	@Test public void deepHiddenManifestsAreDiscoveredWithoutFollowingAnyNestedLink() throws Exception {
		File deep = new File(mods, ".assets/resources/textures");
		for (int i = 0; i < 65; i++) deep = new File(deep, "d");
		manifest(new File(deep, "Real.json"), "Real");
		File outside = new File(context.getCacheDir(), "outside");
		manifest(new File(outside, "sub/Private.json"), "Private");
		write(new File(outside, "private.dll"), "outside");
		Files.createSymbolicLink(new File(deep, "linked.json").toPath(), new File(outside, "sub/Private.json").toPath());
		Files.createSymbolicLink(new File(deep, "Real.dll").toPath(), new File(outside, "private.dll").toPath());
		Files.createSymbolicLink(new File(deep, "broken.pck").toPath(), new File(outside, "absent").toPath());
		Files.createSymbolicLink(new File(mods, "link").toPath(), outside.toPath());
		Files.createSymbolicLink(new File(deep, "cycle").toPath(), mods.toPath());
		List<ExtraSettingsRepository.ModEntry> snapshot = repository.listInstalledModManifestsForDisplay();
		assertEquals(List.of("Real"), ids(snapshot));
		assertFalse(snapshot.get(0).hasDll);
		assertEquals("Real", snapshot.get(0).pckName);
		assertTrue(repository.listInstalledModManifestsUnder(new File(mods, "link/sub")).isEmpty());
		manifest(new File(deep, "Fresh.json"), "Fresh");
		assertSame(snapshot, repository.listInstalledModManifestsForDisplay());
		assertEquals(List.of("Fresh", "Real"), ids(repository.listInstalledModManifests()));
		assertEquals(List.of("Fresh", "Real"), ids(repository.listInstalledModManifestsUnder(deep)));
	}

	@Test public void legacyRecoveryNeverDeletesThroughNestedLinksOrDiscardsBackupForBrokenTarget() throws Exception {
		File broken = new File(mods, "nested/.99.backup-recover");
		manifest(new File(broken, "Saved.json"), "Saved");
		File target = new File(broken.getParentFile(), "99");
		Files.createSymbolicLink(target.toPath(), new File(context.getCacheDir(), "missing").toPath());
		repository.ensureAppDirectories();
		assertThrows(IOException.class, repository::prepareModsForLaunch);
		assertTrue(new File(broken, "Saved.json").isFile());
		assertTrue(Files.isSymbolicLink(target.toPath()));
		Files.delete(target.toPath());
		File outside = new File(context.getCacheDir(), "protected"), sentinel = new File(outside, "sentinel");
		write(sentinel, "keep");
		File incoming = new File(mods, "elsewhere/.313.incoming-old/nested");
		Files.createDirectories(incoming.toPath());
		Files.createSymbolicLink(new File(incoming, "link").toPath(), outside.toPath());
		Files.createSymbolicLink(new File(incoming, "dangling").toPath(), new File(outside, "absent").toPath());
		repository.prepareModsForLaunch();
		assertTrue(sentinel.isFile());
		assertFalse(incoming.getParentFile().exists());
		assertTrue(new File(target, "Saved.json").isFile());
	}

	@Test public void exportRepairsLegacyBeforeWritingAndRestoreInvalidatesEvenInactiveRoots() throws Exception {
		manifest(new File(mods, "w/.123.backup-old/packed.json"), "Archive");
		Uri archive = Uri.parse("content://backup-test/archive");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		shadowOf(context.getContentResolver()).registerOutputStream(archive, bytes);
		repository.exportFullDataBackupZip(archive);
		Set<String> paths = new HashSet<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) paths.add(entry.getName());
		}
		assertTrue(paths.contains("data/files/mods/w/123/Archive.json"));
		assertFalse(paths.contains("data/files/mods/w/.123.backup-old/packed.json"));
		List<?> before = repository.listInstalledModManifestsForDisplay();
		File other = new File(context.getFilesDir(), "instances/other/mods");
		List<?> inactive = ModManifestCache.get(other, List::of);
		ByteArrayOutputStream replacement = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(replacement)) {
			zip.putNextEntry(new ZipEntry("files/mods/New.json"));
			zip.write("{\"id\":\"New\"}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		shadowOf(context.getContentResolver()).registerInputStream(archive, new ByteArrayInputStream(replacement.toByteArray()));
		repository.importFullDataBackupZip(archive);
		assertNotSame(inactive, ModManifestCache.get(other, List::of));
		List<ExtraSettingsRepository.ModEntry> after = repository.listInstalledModManifestsForDisplay();
		assertNotSame(before, after);
		assertEquals(List.of("New"), ids(after));
		shadowOf(context.getContentResolver()).registerInputStream(archive, new ByteArrayInputStream(bytes.toByteArray()));
		repository.importFullDataBackupZip(archive);
		assertEquals(List.of("Archive"), ids(repository.listInstalledModManifestsForDisplay()));
	}

	@Test public void failedRestoreInvalidatesAllRootsWithoutWritingThroughLink() throws Exception {
		File outside = new File(context.getCacheDir(), "sentinel.bin");
		write(outside, "keep");
		Files.createSymbolicLink(new File(context.getFilesDir(), "collision").toPath(), outside.toPath());
		File inactive = new File(context.getFilesDir(), "instances/other/mods");
		List<?> before = ModManifestCache.get(inactive, List::of);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("files/collision"));
			zip.write(1);
			zip.closeEntry();
		}
		Uri uri = Uri.parse("content://backup-test/failed");
		shadowOf(context.getContentResolver()).registerInputStream(uri, new ByteArrayInputStream(bytes.toByteArray()));
		assertThrows(IOException.class, () -> repository.importFullDataBackupZip(uri));
		assertNotSame(before, ModManifestCache.get(inactive, List::of));
		assertArrayEquals("keep".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outside.toPath()));
	}

	private static List<String> ids(List<ExtraSettingsRepository.ModEntry> entries) {
		List<String> ids = new java.util.ArrayList<>();
		for (ExtraSettingsRepository.ModEntry entry : entries) ids.add(entry.modId);
		return ids;
	}

	private static void manifest(File file, String id) throws IOException {
		write(file, "{\"id\":\"" + id + "\"}");
	}

	private static void write(File file, String text) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}
}
