package com.godot.game

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class, shadows = [AndroidLinuxTestShadow::class])
class SteamWorkshopDirectAccessTest {
    private val clients = mutableListOf<OkHttpClient>()
    private val workers = mutableListOf<ExecutorService>()

    @After fun tearDown() {
        clients.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
        workers.forEach { it.shutdownNow() }
        workers.forEach { it.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    @Test fun parentCancelClosesNestedSocketAndDoesNotEnterFallback() {
        val held = Reply(holdHeaders = true, waitForDisconnect = true)
        LocalPeer(held).use { peer ->
            val dns = LoopbackDns()
            val events = CallEvents()
            val client = client(dns) { eventListener(events) }
            val parent = client.newCall(peer.request())
            val result = executeFailure(worker(), parent)
            assertEquals("GET /start HTTP/1.1", peer.nextRequest().line)

            parent.cancel()

            val failure = result.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(failure.error is IOException)
            await(held.disconnected, "Parent cancel must close the actual nested socket")
            val child = events.started.single { it.request().url.host == FORWARD_HOST }
            assertTrue(child.isCanceled())
            assertTrue("The configured listener must still receive parent cancellation", events.canceled.contains(parent))
            assertTrue("The configured listener must still receive child cancellation", events.canceled.contains(child))
            // A canceled parent also prevents OkHttp's original-host exchange from opening a socket.
            // Exception identity distinguishes propagating the route failure from attempting that fallback.
            assertSame(events.failures[child], failure.error)
            assertEquals(listOf(FORWARD_HOST), dns.lookups.toList())
        }
    }

    @Test fun cancelAfterExecuteReturnsStillStopsTheResponseBody() {
        val partial = Reply(body = "a", declaredLength = 2, waitForDisconnect = true)
        LocalPeer(partial).use { peer ->
            val dns = LoopbackDns()
            val events = CallEvents()
            val parent = client(dns) { eventListener(events) }.newCall(peer.request())
            parent.execute().use { response ->
                assertEquals(LOGICAL_HOST, response.request.url.host)
                assertEquals("GET /start HTTP/1.1", peer.nextRequest().line)
                val source = response.body.source()
                assertEquals('a'.code.toByte(), source.readByte())
                val reading = CountDownLatch(1)
                val result = worker().submit(Callable {
                    captureFailure {
                        reading.countDown()
                        source.readByte()
                    }
                })
                await(reading, "The response body reader must start")

                parent.cancel()

                assertTrue(result.get(WAIT_SECONDS, TimeUnit.SECONDS).error is IOException)
                await(partial.disconnected, "Cancel after execute must close the body socket")
                val child = events.started.single { it.request().url.host == FORWARD_HOST }
                assertTrue(child.isCanceled())
                assertTrue(events.canceled.contains(parent))
                assertEquals(listOf(FORWARD_HOST), dns.lookups.toList())
            }
        }
    }

    @Test fun cancelWhileInitialChildIsBeingConstructedCancelsItAfterPublication() {
        assertCancellationDuringChildConstruction(afterRedirect = false)
    }

    @Test fun cancelWhileRedirectChildIsBeingConstructedCancelsTheReplacement() {
        assertCancellationDuringChildConstruction(afterRedirect = true)
    }

    @Test fun cancelDuringRedirectExchangeClosesTheCurrentChildNotJustTheFirst() {
        val held = Reply(holdHeaders = true, waitForDisconnect = true)
        LocalPeer(Reply(code = 302, location = "/next"), held).use { peer ->
            val dns = LoopbackDns()
            val events = CallEvents()
            val parent = client(dns) { eventListener(events) }.newCall(peer.request())
            val result = executeFailure(worker(), parent)
            assertEquals("GET /start HTTP/1.1", peer.nextRequest().line)
            assertEquals("GET /next HTTP/1.1", peer.nextRequest().line)

            parent.cancel()

            val failure = result.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(failure.error is IOException)
            await(held.disconnected, "The redirect child must be canceled")
            val children = events.started.filter { it.request().url.host == FORWARD_HOST }
            assertEquals(2, children.size)
            assertTrue(children.last().isCanceled())
            assertTrue(events.canceled.contains(children.last()))
            assertSame(events.failures[children.last()], failure.error)
            assertEquals(listOf(FORWARD_HOST, FORWARD_HOST), dns.lookups.toList())
        }
    }

    @Test fun ordinaryRouteIOExceptionStillFallsBackToTheOriginalHost() {
        LocalPeer(Reply(disconnectImmediately = true), Reply(body = "original-response")).use { peer ->
            val dns = LoopbackDns()
            val events = CallEvents()
            val parent = client(dns) { eventListener(events) }.newCall(peer.request())

            parent.execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(LOGICAL_HOST, response.request.url.host)
                assertEquals("original-response", response.body.string())
            }

            val routeRequest = peer.nextRequest()
            val originalRequest = peer.nextRequest()
            assertEquals(LOGICAL_HOST, routeRequest.headers["host"])
            assertEquals("$LOGICAL_HOST:${peer.port}", originalRequest.headers["host"])
            assertEquals(listOf(FORWARD_HOST, LOGICAL_HOST), dns.lookups.toList())
            val child = events.started.single { it.request().url.host == FORWARD_HOST }
            assertTrue(events.failures[child] is IOException)
            assertFalse(parent.isCanceled())
        }
    }

    @Test fun socketTimeoutIsStillAnOrdinaryRouteFailureThatCanFallback() {
        LocalPeer(Reply(body = "original-after-timeout")).use { peer ->
            val dns = LoopbackDns()
            val attempts = AtomicInteger()
            val sockets = object : DefaultSocketFactory() {
                override fun createSocket(): Socket = object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) {
                        if (attempts.incrementAndGet() == 1) {
                            throw SocketTimeoutException("Controlled route connect timeout")
                        }
                        super.connect(endpoint, timeout)
                    }
                }
            }
            val parent = client(dns) { socketFactory(sockets) }.newCall(peer.request())

            parent.execute().use { response ->
                assertEquals("original-after-timeout", response.body.string())
            }

            assertEquals("$LOGICAL_HOST:${peer.port}", peer.nextRequest().headers["host"])
            assertEquals(2, attempts.get())
            assertEquals(listOf(FORWARD_HOST, LOGICAL_HOST), dns.lookups.toList())
            assertFalse(parent.isCanceled())
        }
    }

    @Test fun compatibilityRedirectsPreserveLogicalUrlHostMethodBodyAndResponse() {
        LocalPeer { port ->
            listOf(
                Reply(code = 307, location = "http://$FORWARD_HOST:$port/redirected?image=1"),
                Reply(code = 302, location = "/final?image=2"),
                Reply(code = 206, body = "compatibility-response"),
            )
        }.use { peer ->
            val dns = LoopbackDns()
            val request = peer.request().newBuilder()
                .post("workshop-request".toRequestBody("text/plain".toMediaType()))
                .build()

            client(dns).newCall(request).execute().use { response ->
                assertEquals(206, response.code)
                assertEquals("compatibility-response", response.body.string())
                assertEquals("loopback-peer", response.header("X-Route-Reply"))
                assertEquals("http://$LOGICAL_HOST:${peer.port}/final?image=2", response.request.url.toString())
                assertEquals("GET", response.request.method)
            }

            val requests = List(3) { peer.nextRequest() }
            assertEquals(listOf(
                "POST /start HTTP/1.1",
                "POST /redirected?image=1 HTTP/1.1",
                "GET /final?image=2 HTTP/1.1",
            ), requests.map { it.line })
            assertEquals(listOf(LOGICAL_HOST, LOGICAL_HOST, LOGICAL_HOST), requests.map { it.headers["host"] })
            assertEquals(listOf("workshop-request", "workshop-request", ""), requests.map { it.body })
            assertEquals("text/plain; charset=utf-8", requests[1].headers["content-type"])
            assertNull(requests[2].headers["content-type"])
            assertNull(requests[2].headers["content-length"])
            assertEquals(listOf(FORWARD_HOST, FORWARD_HOST, FORWARD_HOST), dns.lookups.toList())
        }
    }

    @Test fun shutdownNowDuringFastFallbackConnectBecomesInterruptedIOExceptionWithoutFallback() {
        val held = Reply(holdHeaders = true, waitForDisconnect = true)
        LocalPeer(held).use { peer ->
            val dns = LoopbackDns()
            val sockets = BlockedConnectSocketFactory()
            val parent = client(dns) { socketFactory(sockets) }.newCall(peer.request())
            val executor = worker()
            val result = executeFailure(executor, parent)
            await(sockets.connected, "A real loopback TCP connection must be in flight")

            executor.shutdownNow()

            val failure = result.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue("Raw InterruptedException must not escape execute()", failure.error is InterruptedIOException)
            assertTrue(failure.error.cause is InterruptedException)
            assertTrue("The worker interrupt flag must be restored", failure.interrupted)
            assertTrue("Exercise OkHttp's real fast-fallback wait, not a test-thrown interruption",
                failure.error.cause!!.stackTrace.any {
                    it.className == "okhttp3.internal.connection.FastFallbackExchangeFinder" && it.methodName == "awaitTcpConnect"
                })
            await(sockets.closed, "Interrupted connect must release its nested socket")
            await(held.disconnected, "The loopback peer must observe connection cancellation")
            assertEquals(listOf(FORWARD_HOST), dns.lookups.toList())
        }
    }

    @Test fun alreadyInterruptedWorkerDoesNotStartARouteOrFallback() {
        LocalPeer(Reply(body = "must-not-be-requested")).use { peer ->
            val dns = LoopbackDns()
            val parent = client(dns).newCall(peer.request())
            val result = worker().submit(Callable {
                Thread.currentThread().interrupt()
                captureFailure { parent.execute().use {} }
            })

            val failure = result.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(failure.error is InterruptedIOException)
            assertTrue(failure.interrupted)
            assertTrue(dns.lookups.isEmpty())
            assertTrue(peer.requests.isEmpty())
        }
    }

    private fun assertCancellationDuringChildConstruction(afterRedirect: Boolean) {
        val replies = if (afterRedirect) arrayOf(Reply(code = 302, location = "/next"), Reply()) else arrayOf(Reply())
        LocalPeer(*replies).use { peer ->
            val dns = LoopbackDns()
            val events = CallEvents()
            val creating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val childNumber = AtomicInteger()
            val gatedChild = AtomicReference<Call>()
            val targetChild = if (afterRedirect) 2 else 1
            val client = client(dns) {
                eventListenerFactory { call ->
                    if (call.request().url.host == FORWARD_HOST && childNumber.incrementAndGet() == targetChild) {
                        // This public factory runs inside newCall, before the interceptor can attach it.
                        // Do not access Call tags here: OkHttp initializes them after the factory returns.
                        gatedChild.set(call)
                        creating.countDown()
                        await(release, "The child construction gate must be released")
                    }
                    events
                }
            }
            val parent = client.newCall(peer.request())
            val result = executeFailure(worker(), parent)
            try {
                if (afterRedirect) assertEquals("GET /start HTTP/1.1", peer.nextRequest().line)
                await(creating, "The child must reach its construction boundary")

                parent.cancel()
                release.countDown()

                val failure = result.get(WAIT_SECONDS, TimeUnit.SECONDS)
                assertTrue(failure.error is IOException)
                assertTrue("A child published after parent cancellation must already be canceled", gatedChild.get().isCanceled())
                assertTrue(events.canceled.contains(gatedChild.get()))
                assertSame(events.failures[gatedChild.get()], failure.error)
                assertTrue(peer.requests.isEmpty())
                assertEquals(if (afterRedirect) listOf(FORWARD_HOST) else emptyList<String>(), dns.lookups.toList())
            } finally {
                release.countDown()
                parent.cancel()
            }
        }
    }

    private fun client(dns: Dns, configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient {
        val context = RuntimeEnvironment.getApplication()
        SteamWorkshopPreferences.setDirectAccessEnabled(context, true)
        return SteamWorkshopDirectAccess.buildClient(context, WorkshopHttpRouteMode.DEFAULT) {
            proxy(Proxy.NO_PROXY)
            dns(dns)
            retryOnConnectionFailure(false)
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
            configure()
        }.also { clients.add(it) }
    }

    private fun worker(): ExecutorService = Executors.newSingleThreadExecutor().also { workers.add(it) }

    private fun executeFailure(executor: ExecutorService, call: Call): Future<ExecutionFailure> =
        executor.submit(Callable { captureFailure { call.execute().use {} } })

    private data class ExecutionFailure(val error: Throwable, val interrupted: Boolean)

    private class CallEvents : EventListener() {
        val started = CopyOnWriteArrayList<Call>()
        val canceled = CopyOnWriteArrayList<Call>()
        val failures = ConcurrentHashMap<Call, IOException>()

        override fun callStart(call: Call) { started.add(call) }
        override fun canceled(call: Call) { canceled.add(call) }
        override fun callFailed(call: Call, ioe: IOException) { failures[call] = ioe }
    }

    private class LoopbackDns : Dns {
        val lookups = CopyOnWriteArrayList<String>()

        override fun lookup(hostname: String): List<InetAddress> {
            check(hostname == LOGICAL_HOST || hostname == FORWARD_HOST) { "Unexpected test DNS host: $hostname" }
            lookups.add(hostname)
            return listOf(LOOPBACK)
        }
    }

    private open class DefaultSocketFactory : SocketFactory() {
        private val delegate = SocketFactory.getDefault()
        override fun createSocket(): Socket = delegate.createSocket()
        override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort)
    }

    private class BlockedConnectSocketFactory : DefaultSocketFactory() {
        val connected = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                super.connect(endpoint, timeout)
                connected.countDown()
                if (!closed.await(30, TimeUnit.SECONDS)) throw SocketTimeoutException("Connect cancellation did not close the socket")
                throw SocketException("Connect socket closed")
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    closed.countDown()
                }
            }
        }
    }

    private class Reply(
        val code: Int = 200,
        val location: String? = null,
        val body: String = "",
        val declaredLength: Int? = null,
        val holdHeaders: Boolean = false,
        val waitForDisconnect: Boolean = false,
        val disconnectImmediately: Boolean = false,
    ) {
        val disconnected = CountDownLatch(1)
    }

    private data class ReceivedRequest(val line: String, val headers: Map<String, String>, val body: String)

    /** A real HTTP/1 loopback peer; no production mocks, sleeps, or public-network access. */
    private class LocalPeer(repliesForPort: (Int) -> List<Reply>) : Closeable {
        constructor(vararg replies: Reply) : this({ replies.toList() })

        private val server = ServerSocket(0, 8, LOOPBACK)
        private val executor = Executors.newSingleThreadExecutor()
        val requests = LinkedBlockingQueue<ReceivedRequest>()
        val port: Int get() = server.localPort
        private val replies = repliesForPort(port)
        @Volatile private var closing = false
        @Volatile private var active: Socket? = null
        private val task = executor.submit {
            try {
                for (reply in replies) {
                    server.accept().use { socket ->
                        active = socket
                        socket.soTimeout = 30_000
                        val input = socket.getInputStream()
                        val request = readRequest(input)
                        if (request == null) {
                            reply.disconnected.countDown()
                            return@use
                        }
                        requests.put(request)
                        if (!reply.disconnectImmediately && !reply.holdHeaders) {
                            val body = reply.body.toByteArray(Charsets.UTF_8)
                            val headers = "HTTP/1.1 ${reply.code} Test\r\n" +
                                "Content-Length: ${reply.declaredLength ?: body.size}\r\n" +
                                "Content-Type: text/plain\r\nX-Route-Reply: loopback-peer\r\nConnection: close\r\n" +
                                (reply.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
                            socket.getOutputStream().apply {
                                write(headers.toByteArray(Charsets.US_ASCII))
                                write(body)
                                flush()
                            }
                        }
                        if (reply.waitForDisconnect) {
                            check(input.read() == -1) { "Unexpected extra request data on held socket" }
                            reply.disconnected.countDown()
                        }
                    }
                    active = null
                }
            } catch (error: IOException) {
                if (!closing) throw error
            }
        }

        fun request(): Request = Request.Builder().url("http://$LOGICAL_HOST:$port/start").build()

        fun nextRequest(): ReceivedRequest = requests.poll(WAIT_SECONDS, TimeUnit.SECONDS)
            ?: throw AssertionError("Missing loopback HTTP request")

        override fun close() {
            closing = true
            server.close()
            active?.close()
            executor.shutdownNow()
            task.get(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        private fun readRequest(input: InputStream): ReceivedRequest? {
            val line = asciiLine(input) ?: return null
            val headers = linkedMapOf<String, String>()
            while (true) {
                val header = asciiLine(input) ?: throw EOFException("EOF in request headers")
                if (header.isEmpty()) break
                val separator = header.indexOf(':')
                check(separator > 0) { "Invalid request header" }
                headers[header.substring(0, separator).lowercase(Locale.ROOT)] = header.substring(separator + 1).trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = input.readNBytes(length)
            if (body.size != length) throw EOFException("EOF in request body")
            return ReceivedRequest(line, headers, body.toString(Charsets.UTF_8))
        }

        private fun asciiLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                when (val value = input.read()) {
                    -1 -> if (bytes.size() == 0) return null else throw EOFException("EOF in HTTP line")
                    '\n'.code -> return bytes.toString(Charsets.US_ASCII.name())
                    '\r'.code -> Unit
                    else -> bytes.write(value)
                }
            }
        }
    }

    companion object {
        private const val WAIT_SECONDS = 5L
        private const val LOGICAL_HOST = "images.steamusercontent.com"
        private const val FORWARD_HOST = "steamcommunity.rmbgame.net"
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        private fun await(latch: CountDownLatch, message: String) {
            assertTrue(message, latch.await(WAIT_SECONDS, TimeUnit.SECONDS))
        }

        private fun captureFailure(action: () -> Unit): ExecutionFailure {
            return try {
                action()
                ExecutionFailure(AssertionError("Expected an I/O failure"), Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                ExecutionFailure(error, Thread.currentThread().isInterrupted)
            } finally {
                // Record before clearing, so an executor worker cannot leak this flag to another test.
                Thread.interrupted()
            }
        }
    }
}
