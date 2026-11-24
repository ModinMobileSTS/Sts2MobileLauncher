package com.godot.game;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ModManifestCacheTest {
	@Rule public TemporaryFolder temp = new TemporaryFolder();

	@After public void clearCache() {
		ModManifestCache.invalidateAll();
	}

	@Test public void sameRootSharesOneFlightAndInvalidationDuringScanSupersedesItsResult() throws Exception {
		File root = temp.newFolder("mods"), other = temp.newFolder("other");
		File alias = new File(temp.getRoot(), "alias");
		Files.createSymbolicLink(alias.toPath(), root.toPath());
		CountDownLatch scanning = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		ModManifestCache.Scan scan = () -> {
			if (reads.incrementAndGet() != 1) return List.of();
			scanning.countDown();
			await(release);
			return java.util.Collections.singletonList(null); // Deliberately different pre-write snapshot.
		};
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			Future<List<ExtraSettingsRepository.ModEntry>> a = pool.submit(() -> ModManifestCache.get(root, scan));
			await(scanning);
			Future<List<ExtraSettingsRepository.ModEntry>> b = pool.submit(() -> {
				second.countDown();
				return ModManifestCache.get(alias, scan);
			});
			await(second);
			List<?> independent = pool.submit(() -> ModManifestCache.get(other, List::of)).get(5, TimeUnit.SECONDS);
			assertEquals(1, reads.get());
			ModManifestCache.invalidatePath(new File(alias, "sub/file.json"));
			release.countDown();
			List<?> result = a.get(5, TimeUnit.SECONDS);
			assertSame(result, b.get(5, TimeUnit.SECONDS));
			assertTrue(result.isEmpty());
			assertSame(result, ModManifestCache.get(root, scan));
			assertEquals(2, reads.get());
			assertSame(independent, ModManifestCache.get(other, List::of));
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test public void pathsInvalidateOnlyIntersectingRootsAcrossTrustedPathSpellings() throws Exception {
		File real = temp.newFolder("data"), alias = new File(temp.getRoot(), "user-zero");
		Files.createSymbolicLink(alias.toPath(), real.toPath());
		File root = new File(real, "files/instances/one/mods");
		File sibling = new File(real, "files/instances/one/mods-other");
		Files.createDirectories(root.toPath());
		Files.createDirectories(sibling.toPath());
		List<?> a = ModManifestCache.get(root, List::of), b = ModManifestCache.get(sibling, List::of);
		ModManifestCache.invalidatePath(new File(alias, "files/instances/one/mods/item/removed.json"));
		List<?> afterChild = ModManifestCache.get(root, List::of);
		assertNotSame(a, afterChild);
		assertSame(b, ModManifestCache.get(sibling, List::of));
		File linked = new File(root, "linked.json");
		Files.createSymbolicLink(linked.toPath(), temp.newFile("outside.json").toPath());
		ModManifestCache.invalidatePath(new File(alias, "files/instances/one/mods/linked.json"));
		List<?> afterLink = ModManifestCache.get(root, List::of);
		assertNotSame(afterChild, afterLink);
		assertSame(b, ModManifestCache.get(sibling, List::of));
		ModManifestCache.invalidatePath(new File(alias, "files/instances/one"));
		assertNotSame(afterLink, ModManifestCache.get(root, List::of));
		assertNotSame(b, ModManifestCache.get(sibling, List::of));
	}

	@Test public void failedScanCanRetryAndSnapshotsDoNotExposeTheirBackingList() throws Exception {
		File root = temp.newFolder("mods");
		assertThrows(IOException.class, () -> ModManifestCache.get(root, () -> { throw new IOException("unreadable"); }));
		List<ExtraSettingsRepository.ModEntry> mutable = new ArrayList<>();
		List<ExtraSettingsRepository.ModEntry> snapshot = ModManifestCache.get(root, () -> mutable);
		mutable.add(null);
		assertTrue(snapshot.isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.add(null));
		assertSame(snapshot, ModManifestCache.get(root, List::of));
	}

	private static void await(CountDownLatch latch) throws IOException {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for scanner");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}
}
