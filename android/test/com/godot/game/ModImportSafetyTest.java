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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ModImportSafetyTest {
	private Context context;
	private ExtraSettingsRepository repository;

	@Before public void setup() {
		context = RuntimeEnvironment.getApplication();
		repository = new ExtraSettingsRepository(context);
	}

	@Test public void manifestCannotCreateAliasesOutsideStaging() throws Exception {
		for (String field : List.of("id", "pck_name")) {
			File zip = archive(new JSONObject().put("id", "SafeMod").put("pck_name", "payload").put(field, "../escaped"));
			assertThrows(IOException.class, () -> repository.prepareDownloadedModImport(zip, "mod.zip"));
		}
	}

	@Test public void rootedAndWindowsPathsAreRejectedBeforeImport() throws Exception {
		File outside = new File(context.getFilesDir(), "sentinel");
		Files.write(outside.toPath(), ("keep").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		for (String id : List.of(outside.getAbsolutePath(), "..\\escaped", "C:escaped", ".", "..")) {
			File zip = archive(new JSONObject().put("id", id).put("pck_name", "payload"));
			assertThrows(IOException.class, () -> repository.prepareDownloadedModImport(zip, "mod.zip"));
			assertEquals("keep", new String(Files.readAllBytes(outside.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	@Test public void validUnicodeAndDottedNamesRetainRuntimePayloadAliases() throws Exception {
		String id = "模组.Test-1";
		File zip = archive(new JSONObject().put("id", id).put("pck_name", "payload"));
		ExtraSettingsRepository.PreparedModImport prepared = repository.prepareDownloadedModImport(zip, "mod.zip");
		try {
			assertEquals(1, prepared.incomingEntries.size());
			assertEquals(id, prepared.incomingEntries.get(0).modId);
			assertTrue(prepared.incomingEntries.get(0).hasDll);
			assertEquals("fixture-dll", new String(Files.readAllBytes(new File(prepared.stagingRoot, id + ".dll").toPath()), java.nio.charset.StandardCharsets.UTF_8));
			assertEquals(id, new JSONObject(new String(Files.readAllBytes(new File(prepared.stagingRoot, id + ".json").toPath()), java.nio.charset.StandardCharsets.UTF_8)).getString("id"));
			assertFalse(new File(prepared.stagingRoot, "mod_manifest.json").exists());
		} finally {
			repository.discardPreparedModImport(prepared);
		}
	}

	@Test public void existingSymlinkCannotSupplyPayloadFromOutsideModDirectory() throws Exception {
		File mods = repository.getModsRootDir();
		Files.createDirectories(mods.toPath());
		File outside = new File(context.getFilesDir(), "outside.dll");
		Files.write(outside.toPath(), ("private-bytes").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.write(new File(mods, "mod_manifest.json").toPath(), (new JSONObject().put("id", "SafeMod").put("pck_name", "payload").toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.createSymbolicLink(new File(mods, "payload.dll").toPath(), outside.toPath());
		repository.ensureAppDirectories();
		assertFalse(new File(mods, "SafeMod.dll").exists());
		assertEquals("private-bytes", new String(Files.readAllBytes(outside.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(new File(mods, "mod_manifest.json").isFile());
	}

	private File archive(JSONObject manifest) throws Exception {
		File zip = File.createTempFile("mod-safety-", ".zip", context.getCacheDir());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
			out.putNextEntry(new ZipEntry("mod_manifest.json"));
			out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("payload.dll"));
			out.write("fixture-dll".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return zip;
	}
}
