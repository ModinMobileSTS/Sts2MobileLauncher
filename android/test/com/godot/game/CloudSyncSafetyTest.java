package com.godot.game;

import android.content.Context;
import com.godot.game.steam.cloud.Sts2SteamCloudSyncManager;
import com.godot.game.webdav.WebDavSyncManager;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class CloudSyncSafetyTest {
	@Test public void localOnlyProgressSurvivesPullAndRemainsUploadable() throws Exception {
		for (Cloud cloud : clouds()) {
			cloud.agree("progress.save", "A");
			Object local = cloud.local("progress.save", "B");
			Object remote = cloud.remote("progress.save", "A");
			assertEquals(List.of(), cloud.plan(false, List.of(local), List.of(remote), false));
			cloud.persist(List.of(local), List.of(remote));
			assertEquals(List.of(local), cloud.plan(true, List.of(local), List.of(remote), false));
			assertEquals(List.of(remote), cloud.plan(false, List.of(local), List.of(remote), true));
		}
	}

	@Test public void skippedUploadDoesNotForgetRemoteDivergence() throws Exception {
		for (Cloud cloud : clouds()) {
			cloud.agree("progress.save", "A");
			Object local = cloud.local("progress.save", "A");
			Object remote = cloud.remote("progress.save", "B");
			assertEquals(List.of(), cloud.plan(true, List.of(local), List.of(remote), false));
			cloud.persist(List.of(local), List.of(remote));
			assertEquals(List.of(remote), cloud.plan(false, List.of(local), List.of(remote), false));
			Object divergent = cloud.local("progress.save", "C");
			cloud.expectConflict(true, List.of(divergent), List.of(remote));
			cloud.expectConflict(false, List.of(divergent), List.of(remote));
			assertEquals(List.of(divergent), cloud.plan(true, List.of(divergent), List.of(remote), true));
		}
	}

	@Test public void partialSyncAdvancesOnlyAgreedFiles() throws Exception {
		for (Cloud cloud : clouds()) {
			cloud.agree("progress.save", "A");
			cloud.agree("current_run.save", "A");
			cloud.persist(List.of(cloud.local("progress.save", "B"), cloud.local("current_run.save", "A")),
				List.of(cloud.remote("progress.save", "B"), cloud.remote("current_run.save", "B")));
			Object changed = cloud.local("progress.save", "C");
			assertEquals(List.of(changed), cloud.plan(true, List.of(changed), List.of(cloud.remote("progress.save", "B")), false));
			cloud.expectConflict(true, List.of(cloud.local("current_run.save", "C")), List.of(cloud.remote("current_run.save", "B")));
		}
	}

	@Test public void absentOrDivergentLegacyBaselineRequiresExplicitChoice() throws Exception {
		for (Cloud cloud : clouds()) {
			Object local = cloud.local("progress.save", "A");
			Object remote = cloud.remote("progress.save", "B");
			cloud.expectConflict(false, List.of(local), List.of(remote));
			cloud.agree("progress.save", "A");
			JSONObject old = cloud.baseline();
			old.getJSONArray("entries").getJSONObject(0).put("remote_sha1", "B");
			Files.write(cloud.baselineFile().toPath(), (old.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			cloud.expectConflict(false, List.of(local), List.of(remote));
			cloud.expectConflict(true, List.of(local), List.of(remote));
			cloud.persist(List.of(local), List.of(remote));
			cloud.expectConflict(true, List.of(local), List.of(remote));
			assertEquals(List.of(remote), cloud.plan(false, List.of(), List.of(remote), false));
			assertEquals(List.of(), cloud.plan(false, List.of(local), List.of(cloud.remote("progress.save", "A")), false));
		}
	}

	private static List<Cloud> clouds() throws Exception {
		Context context = RuntimeEnvironment.getApplication();
		return List.of(new Cloud(new Sts2SteamCloudSyncManager(context)), new Cloud(new WebDavSyncManager(context)));
	}

	// Exercise production decisions and on-disk baseline transitions without a live cloud account.
	private static final class Cloud {
		final Object manager;
		Cloud(Object manager) throws Exception {
			this.manager = manager;
			Files.deleteIfExists(baselineFile().toPath());
		}
		Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
			Method method = manager.getClass().getDeclaredMethod(name, types);
			method.setAccessible(true);
			try { return method.invoke(manager, args); }
			catch (InvocationTargetException e) { throw (Exception)e.getCause(); }
		}
		Object entry(String type, Object... args) throws Exception {
			Class<?> entryType = Class.forName(manager.getClass().getName() + "$" + type);
			Constructor<?> constructor = entryType.getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			return constructor.newInstance(args);
		}
		Object local(String path, String hash) throws Exception {
			return entry("LocalEntry", path, path, new File(path), 1L, 0L, hash);
		}
		Object remote(String path, String hash) throws Exception {
			return manager instanceof Sts2SteamCloudSyncManager
				? entry("RemoteEntry", path, path, 1L, 0L, "", "", hash)
				: entry("RemoteEntry", path, path, 1L, 0L, "", hash);
		}
		File baselineFile() throws Exception { return (File)invoke("getBaselineFile", new Class<?>[0]); }
		JSONObject baseline() throws Exception {
			File file = baselineFile();
			return file.isFile() ? new JSONObject(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8)) : null;
		}
		void persist(List<?> local, List<?> remote) throws Exception {
			invoke("writeBaseline", new Class<?>[]{List.class, List.class}, local, remote);
		}
		void agree(String path, String hash) throws Exception { persist(List.of(local(path, hash)), List.of(remote(path, hash))); }
		List<?> plan(boolean upload, List<?> local, List<?> remote, boolean force) throws Exception {
			return (List<?>)invoke(upload ? "planUploads" : "planDownloads",
				new Class<?>[]{List.class, List.class, JSONObject.class, boolean.class},
				upload ? local : remote, upload ? remote : local, baseline(), force);
		}
		void expectConflict(boolean upload, List<?> local, List<?> remote) throws Exception {
			Exception error = assertThrows(Exception.class, () -> plan(upload, local, remote, false));
			assertTrue(error instanceof Sts2SteamCloudSyncManager.CloudConflictException
				|| error instanceof WebDavSyncManager.CloudConflictException);
		}
	}
}
