package com.godot.game;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class WorkshopImageCancellationTest {
    private SteamWorkshopActivity workshop;
    private ActivityController<Activity> host;
    private FrameLayout content;
    private Object loader;
    private ThreadPoolExecutor workers;
    private final AtomicReference<Throwable> uncaught = new AtomicReference<>();

    @Before public void setUp() throws Exception {
        // Attach targets to a real window, without running Workshop's online catalog query.
        host = Robolectric.buildActivity(Activity.class).setup();
        content = new FrameLayout(host.get());
        host.get().setContentView(content);
        workshop = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
        workshop.setTheme(R.style.Theme_Sts2ExtraSettings);
        Class<?> type = Class.forName("com.godot.game.SteamWorkshopActivity$WorkshopImageLoader");
        Constructor<?> constructor = type.getDeclaredConstructor(SteamWorkshopActivity.class);
        constructor.setAccessible(true);
        loader = constructor.newInstance(workshop);
        workers = (ThreadPoolExecutor) field(loader, "workers");
        ThreadFactory original = workers.getThreadFactory();
        workers.setThreadFactory(runnable -> {
            Thread thread = original.newThread(runnable);
            thread.setUncaughtExceptionHandler((ignored, error) -> uncaught.set(error));
            return thread;
        });
    }

    @After public void tearDown() throws Exception {
        invoke(loader, "shutdown");
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        ((Handler) field(workshop, "mainHandler")).removeCallbacksAndMessages(null);
        ((ExecutorService) field(workshop, "libraryExecutor")).shutdownNow();
        host.pause().stop().destroy();
        assertNull("An image worker must not reach the app's uncaught-exception handler", uncaught.get());
    }

    @Test public void completedImagesStillDecodePopulateTargetsAndReuseTheCache() throws Exception {
        try (ImageServer server = new ImageServer(false)) {
            ImageView first = target();
            ImageView second = target();
            load(server.url(), first);
            load(server.url(), second);
            assertTrue(server.headersSent.await(5, TimeUnit.SECONDS));
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            server.finished.get(5, TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
            Bitmap firstBitmap = ((BitmapDrawable) first.getDrawable()).getBitmap();
            assertEquals(Color.GREEN, firstBitmap.getPixel(0, 0));
            assertEquals(8, firstBitmap.getWidth());
            assertSame(firstBitmap, ((BitmapDrawable) second.getDrawable()).getBitmap());
            ImageView cached = target();
            load(server.url(), cached);
            assertSame(firstBitmap, ((BitmapDrawable) cached.getDrawable()).getBitmap());
        }
    }

    @Test public void closingTheLoaderDuringConnectCancelsWithoutRetryOrUncaughtInterruption() throws Exception {
        PendingConnectSockets sockets = new PendingConnectSockets();
        OkHttpClient client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .socketFactory(sockets).callTimeout(30, TimeUnit.SECONDS).build();
        for (String name : new String[] { "defaultClient", "originalClient", "directClient" }) {
            setField(loader, name, client);
        }
        ImageView image = target();
        try {
            load("http://127.0.0.1:9/preview.png", image);
            assertTrue(sockets.started.await(5, TimeUnit.SECONDS));
            invoke(loader, "shutdown");
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            ShadowLooper.idleMainLooper();
            assertEquals(1, sockets.attempts.get());
            assertTrue(sockets.closed.await(5, TimeUnit.SECONDS));
            assertFalse(image.getDrawable() instanceof BitmapDrawable);
        } finally {
            sockets.closed.countDown();
            client.connectionPool().evictAll();
        }
    }

    @Test public void destroyingTheViewerStopsABodyReadAndDoesNotStartAnotherRoute() throws Exception {
        try (ImageServer server = new ImageServer(true)) {
            ActivityController<WorkshopImageViewerActivity> viewer = Robolectric.buildActivity(
                WorkshopImageViewerActivity.class,
                WorkshopImageViewerActivity.createIntent(host.get(), server.url()));
            viewer.get().setTheme(R.style.Theme_Sts2ExtraSettings);
            viewer.setup();
            try {
                ShadowLooper.idleMainLooper();
                assertTrue(server.headersSent.await(5, TimeUnit.SECONDS));
                ExecutorService worker = (ExecutorService) field(viewer.get(), "worker");
                viewer.pause().stop().destroy();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(-1, (int) server.finished.get(5, TimeUnit.SECONDS));
                ShadowLooper.idleMainLooper();
                assertNull(field(viewer.get(), "bitmap"));
                assertNull(field(viewer.get(), "activeCall"));
            } finally {
                if (!(boolean) field(viewer.get(), "destroyed")) viewer.pause().stop().destroy();
            }
        }
    }

    private ImageView target() {
        ImageView image = new ImageView(host.get());
        content.addView(image, new FrameLayout.LayoutParams(32, 32));
        assertTrue(image.isAttachedToWindow());
        return image;
    }

    private void load(String url, ImageView target) throws Exception {
        Method load = loader.getClass().getDeclaredMethod("load", String.class, ImageView.class);
        load.setAccessible(true);
        load.invoke(loader, url, target);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static final class PendingConnectSockets extends SocketFactory {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger attempts = new AtomicInteger();
        @Override public Socket createSocket() {
            return new Socket() {
                @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    attempts.incrementAndGet();
                    started.countDown();
                    try {
                        if (!closed.await(10, TimeUnit.SECONDS)) throw new SocketException("Smoke connect deadline");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    throw new SocketException("Cancelled connection");
                }
                @Override public synchronized void close() throws IOException { closed.countDown(); super.close(); }
            };
        }
        @Override public Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress h, int p) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
    }

    /** Real loopback HTTP image; the held-body mode observes the client closing its socket. */
    private static final class ImageServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final ExecutorService peer = Executors.newSingleThreadExecutor();
        final CountDownLatch headersSent = new CountDownLatch(1);
        final Future<Integer> finished;
        volatile Socket active;
        ImageServer(boolean holdBody) throws IOException {
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.GREEN);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            byte[] bytes = png.toByteArray();
            finished = peer.submit(() -> {
                try (Socket socket = server.accept()) {
                    active = socket;
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    while (!readLine(input).isEmpty()) {}
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (!holdBody) socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                    headersSent.countDown();
                    return holdBody ? input.read() : 0;
                }
            });
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/preview.png"; }
        @Override public void close() throws Exception {
            server.close();
            if (active != null) active.close();
            peer.shutdownNow();
            assertTrue(peer.awaitTermination(5, TimeUnit.SECONDS));
        }
        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1 && value != '\n') if (value != '\r') line.write(value);
            if (value == -1) throw new IOException("EOF in request headers");
            return line.toString(StandardCharsets.US_ASCII.name());
        }
    }
}
