package com.godot.game;

import android.content.Context;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = AndroidLinuxTestShadow.class)
public class FileBrowserManifestTest {
	private Context context;
	private ExtraSettingsRepository repository;

	@Before public void setup() {
		context = RuntimeEnvironment.getApplication();
		repository = new ExtraSettingsRepository(context);
		ModManifestCache.invalidateAll();
	}

	@After public void clearCache() {
		ModManifestCache.invalidateAll();
	}

	@Test public void pasteEditRenameAndDeleteInvalidateTheAffectedProfileEvenWhenInactive() throws Exception {
		LaunchProfileManager profiles = new LaunchProfileManager(context);
		File game = profiles.getPayloadGameDir("browser-fixture");
		write(new File(game, PayloadManager.PCK_FILE_NAME), "fixture");
		write(new File(game, PayloadManager.STS2_DLL_PATH), "fixture");
		write(new File(game, PayloadManager.MANIFEST_FILE_NAME), "{\"identity\":{\"release_info\":{\"version\":\"fixture\"}}}");
		LaunchProfileManager.LaunchProfile shared = profiles.createProfile("browser-fixture", "Shared", "global", "global",
			"", "", LaunchProfileManager.COMPAT_SELECTION_MANUAL, true);
		File sharedDir = new File(repository.getModsRootDir(), "Workshop/public/1/wrapper");
		File sharedManifest = new File(sharedDir, "A.json");
		write(sharedManifest, "{\"id\":\"A\"}");
		List<?> sharedBefore = display();
		LaunchProfileManager.LaunchProfile isolated = profiles.createProfile("browser-fixture", "Isolated", "global", "isolated",
			"", "", LaunchProfileManager.COMPAT_SELECTION_MANUAL, true);
		File isolatedDir = new File(repository.getModsRootDir(), "Workshop/public/2/wrapper");
		Files.createDirectories(isolatedDir.getParentFile().toPath());
		assertTrue(display().isEmpty());
		profiles.selectProfile(shared.id);
		FileBrowserSupport.pasteEntry(sharedDir, isolatedDir);
		assertSame("copy does not mutate its source", sharedBefore, display());
		profiles.selectProfile(isolated.id);
		assertEquals("A", display().get(0).modId);

		File isolatedManifest = new File(isolatedDir, "A.json");
		FileBrowserSupport.saveEditedText(isolatedManifest, "{\"id\":\"B\"}");
		assertEquals("B", display().get(0).modId);
		File moved = new File(sharedDir, "B.json");
		assertTrue(FileBrowserSupport.renameEntry(isolatedManifest, moved));
		assertTrue(display().isEmpty());
		profiles.selectProfile(shared.id);
		assertEquals(2, display().size());
		FileBrowserSupport.deleteEntry(sharedDir);
		assertTrue(display().isEmpty());
	}

	@Test public void interruptedRecursiveCopyAndDeleteDoNotLeaveWarmSnapshots() throws Exception {
		File destination = new File(repository.getModsRootDir(), "Workshop/public/item/wrapper");
		Files.createDirectories(destination.getParentFile().toPath());
		assertTrue(display().isEmpty());
		File source = Files.createTempDirectory(context.getCacheDir().toPath(), "paste").toFile();
		File manifest = new File(source, "A.json");
		write(manifest, "{\"id\":\"A\"}");
		// Deterministic mid-copy failure: the second child disappears after enumeration.
		File changingSource = new File(source.getPath()) {
			@Override public File[] listFiles() { return new File[] {manifest, new File(source, "gone")}; }
		};
		assertThrows(IOException.class, () -> FileBrowserSupport.pasteEntry(changingSource, destination));
		assertEquals("A", display().get(0).modId);

		File undeletableParent = new File(destination.getPath()) {
			@Override public boolean delete() { return false; }
		};
		assertThrows(IOException.class, () -> FileBrowserSupport.deleteEntry(undeletableParent));
		assertTrue(destination.isDirectory());
		assertTrue(display().isEmpty());
	}

	@Test public void deletingPrivateTreeDoesNotFollowLinksIntoAnotherModsRoot() throws Exception {
		File manifest = new File(repository.getModsRootDir(), "Workshop/public/item/A.json");
		write(manifest, "{\"id\":\"A\"}");
		List<?> snapshot = display();
		File folder = Files.createTempDirectory(context.getFilesDir().toPath(), "browser").toFile();
		Files.createSymbolicLink(new File(folder, "linked-item").toPath(), manifest.getParentFile().toPath());
		Files.createSymbolicLink(new File(folder, "missing").toPath(), new File(folder, "absent").toPath());
		FileBrowserSupport.deleteEntry(folder);
		assertFalse(folder.exists());
		assertTrue(manifest.isFile());
		assertSame(snapshot, display());
	}

	@Test public void failedDocumentImportInvalidatesAfterClosingThePartialFile() throws Exception {
		File parent = new File(repository.getModsRootDir(), "Workshop/public/item");
		File manifest = new File(parent, "A.json");
		write(manifest, "{\"id\":\"A\"}");
		assertEquals("A", display().get(0).modId);
		// Leave an out-of-band edit pending. Only the import can invalidate this root.
		write(manifest, "{\"id\":\"B\"}");
		try (ActivityController<FileBrowserActivity> host = Robolectric.buildActivity(FileBrowserActivity.class)) {
			FileBrowserActivity browser = host.get();
			Uri uri = Uri.parse("content://browser-fixture/interrupted");
			shadowOf(browser.getContentResolver()).registerInputStream(uri, new InputStream() {
				boolean sentByte;
				@Override public int read() throws IOException {
					if (sentByte) throw new IOException("provider disconnected");
					sentByte = true;
					return 1;
				}
			});
			Method method = FileBrowserActivity.class.getDeclaredMethod("importDocuments", List.class, File.class);
			method.setAccessible(true);
			InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> method.invoke(browser, List.of(uri), parent));
			assertTrue(error.getCause() instanceof IOException);
			assertEquals(1L, new File(parent, browser.getString(R.string.file_browser_imported_file_fallback_name)).length());
		}
		assertEquals("B", display().get(0).modId);
	}

	private List<ExtraSettingsRepository.ModEntry> display() {
		return repository.listInstalledModManifestsForDisplay();
	}

	private static void write(File file, String content) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}
}
