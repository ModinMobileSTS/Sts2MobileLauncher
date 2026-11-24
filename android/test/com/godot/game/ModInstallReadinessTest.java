package com.godot.game;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ModInstallReadinessTest {
	private ActivityController<AppCompatActivity> screen() {
		ActivityController<AppCompatActivity> host = Robolectric.buildActivity(AppCompatActivity.class);
		host.get().setTheme(R.style.Theme_Sts2ExtraSettings);
		return host.setup();
	}

	@Test public void waitingForInstallAcceptsCommittedFilesOnceAndOnlyInForeground() throws Exception {
		try (ActivityController<AppCompatActivity> host = screen()) {
			AppCompatActivity owner = host.get();
			ExtraSettingsRepository repo = new ExtraSettingsRepository(owner);
			File mods = repo.getModsRootDir();
			ModInstallStore store = new ModInstallStore(owner.getFilesDir(), mods);
			File stage = Files.createTempDirectory(owner.getCacheDir().toPath(), "readiness").toFile();
			byte[] manifest = "{\"id\":\"example\"}".getBytes(StandardCharsets.UTF_8);
			Files.write(new File(stage, "example.json").toPath(), manifest);
			AtomicInteger busy = new AtomicInteger(), ready = new AtomicInteger();
			AtomicBoolean attemptedOnMain = new AtomicBoolean();
			try (ModInstallStore.Transaction tx = store.beginInstall(stage, "", List.of(new ModInstallStore.Module(
				"example", "example.json", "1", List.of())), List.of(), true, false, false)) {
				ModInstallReadiness.run(owner, () -> {
					if (Looper.myLooper() == Looper.getMainLooper()) attemptedOnMain.set(true);
					repo.ensureAppDirectories();
				}, busy::incrementAndGet, ready::incrementAndGet, e -> { throw new AssertionError(e); });
				await(() -> busy.get() == 1);
				assertEquals(0, ready.get());
				// A different profile must not be held up by the transaction for this root.
				new ModInstallStore(owner.getFilesDir(), new File(owner.getFilesDir(), "instances/other/mods")).recover();
				host.pause();
				tx.commit();
			}
			advanceTimers();
			assertEquals(0, ready.get());
			host.resume();
			await(() -> ready.get() != 0);
			advanceTimers();
			assertEquals(1, ready.get());
			assertEquals(1, busy.get());
			assertFalse(attemptedOnMain.get());
			assertArrayEquals(manifest, Files.readAllBytes(new File(mods, "example.json").toPath()));
		}
	}

	@Test public void cancelledAndDestroyedWaitersDoNotContinueAfterRollback() throws Exception {
		AtomicInteger ready = new AtomicInteger();
		try (ActivityController<AppCompatActivity> host = screen()) {
			AppCompatActivity owner = host.get();
			ExtraSettingsRepository repo = new ExtraSettingsRepository(owner);
			ModInstallStore store = new ModInstallStore(owner.getFilesDir(), repo.getModsRootDir());
			try (ModInstallStore.Transaction tx = store.beginRemoval(List.of(), "")) {
				AtomicInteger waiting = new AtomicInteger();
				Runnable cancel = ModInstallReadiness.run(owner, repo::ensureAppDirectories, waiting::incrementAndGet,
					ready::incrementAndGet, e -> { throw new AssertionError(e); });
				await(() -> waiting.get() == 1);
				cancel.run();
				ModInstallReadiness.run(owner, repo::ensureAppDirectories, waiting::incrementAndGet,
					ready::incrementAndGet, e -> { throw new AssertionError(e); });
				await(() -> waiting.get() == 2);
				host.pause().stop().destroy();
				tx.rollback();
			}
			advanceTimers();
			assertEquals(0, ready.get());
		}
	}

	@Test public void preparedGameIntentCannotStartEngineWhileInstallIsOpen() throws Exception {
		Application app = RuntimeEnvironment.getApplication();
		ExtraSettingsPreferences.setFirstRunSetupCompleted(app, true);
		File mods = new ExtraSettingsRepository(app).getModsRootDir();
		Intent intent = new Intent(app, GodotApp.class).putExtra("launch_prepared", true);
		try (ModInstallStore.Transaction tx = new ModInstallStore(app.getFilesDir(), mods).beginRemoval(List.of(), "");
			ActivityController<GodotApp> host = Robolectric.buildActivity(GodotApp.class, intent)) {
			host.get().setTheme(R.style.GodotAppSplashTheme);
			host.create();
			assertNull(host.get().getGodot());
			assertTrue(host.get().isFinishing());
			Intent waiting = shadowOf(host.get()).getNextStartedActivity();
			assertNotNull(waiting);
			assertEquals(GodotLaunchRetryActivity.class.getName(), waiting.getComponent().getClassName());
			assertEquals("none", StartupHealthTracker.describePendingLaunch(app));
		}
	}

	@Test public void corruptReceiptIsReportedInsteadOfRetriedOrAccepted() throws Exception {
		try (ActivityController<AppCompatActivity> host = screen()) {
			AppCompatActivity owner = host.get();
			ExtraSettingsRepository repo = new ExtraSettingsRepository(owner);
			repo.ensureAppDirectories();
			File[] roots = new File(owner.getFilesDir(), "launcher/mod-installs").listFiles();
			assertNotNull(roots);
			File index = new File(roots[0], "index.json");
			byte[] invalid = "invalid receipt".getBytes(StandardCharsets.UTF_8);
			Files.write(index.toPath(), invalid);
			AtomicInteger attempts = new AtomicInteger();
			AtomicReference<Exception> failure = new AtomicReference<>();
			ModInstallReadiness.run(owner, () -> {
				attempts.incrementAndGet();
				repo.ensureAppDirectories();
			}, () -> fail("corruption is not contention"), () -> fail("corrupt receipts must block launch"), failure::set);
			await(() -> failure.get() != null);
			advanceTimers();
			assertTrue(failure.get() instanceof IOException);
			assertEquals(1, attempts.get());
			assertArrayEquals(invalid, Files.readAllBytes(index.toPath()));
		}
	}

	private static void advanceTimers() {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			advanceTimers();
			Thread.sleep(5);
		}
		assertTrue("Readiness callback timed out", condition.getAsBoolean());
	}
}
