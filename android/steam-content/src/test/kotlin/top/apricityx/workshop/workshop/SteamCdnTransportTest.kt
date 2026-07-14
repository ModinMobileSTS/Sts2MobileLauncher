package top.apricityx.workshop.workshop

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Test
import top.apricityx.workshop.steam.protocol.CdnServer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SteamCdnTransportTest {
    @Test
    fun buildsExplicitProxyAndOriginRoutesEvenWhenEndpointsMatch() {
        val transport = SteamCdnTransport(OkHttpClient())
        val origin = server(host = "origin.example", type = "CDN", httpsSupport = "optional")
        val proxy = server(
            host = "proxy.example",
            type = "SteamCache",
            httpsSupport = "optional",
            useAsProxy = true,
            proxyTemplate = "/proxy/%host%%path%",
        )

        val routes = transport.buildRequestRoutes(origin, proxy)

        assertEquals(
            listOf(
                SteamCdnRouteKind.PROXY,
                SteamCdnRouteKind.ORIGIN,
                SteamCdnRouteKind.PROXY,
                SteamCdnRouteKind.ORIGIN,
            ),
            routes.map(SteamCdnRequestRoute::kind),
        )
        assertEquals(listOf("https", "https", "http", "http"), routes.map { it.endpoint.scheme })
        assertEquals(
            "https://proxy.example/proxy/origin.example/depot/file?token=one",
            transport.buildRequestUrl(origin, routes[0], "depot/file", "?token=one").toString(),
        )
        assertEquals(
            "https://origin.example/depot/file?token=one",
            transport.buildRequestUrl(origin, routes[1], "depot/file", "token=one").toString(),
        )
    }

    @Test
    fun bypassProxyTypeUsesOnlyOriginRoutes() {
        val transport = SteamCdnTransport(OkHttpClient())
        val origin = server(
            host = "origin.example",
            type = "CDN",
            bypassProxiesOfType = listOf("steamcache"),
        )
        val proxy = server(
            host = "proxy.example",
            type = "SteamCache",
            useAsProxy = true,
            proxyTemplate = "/proxy/%host%%path%",
        )

        assertEquals(
            listOf(SteamCdnRouteKind.ORIGIN),
            transport.buildRequestRoutes(origin, proxy).map(SteamCdnRequestRoute::kind),
        )
    }

    @Test
    fun proxyFailureFallsBackToOriginInRouteOrder() = runBlocking {
        val hosts = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val host = chain.request().url.host
                    hosts += host
                    response(chain, if (host == "proxy.example") 502 else 200, if (host == "proxy.example") "" else "data")
                },
            )
            .build()
        val transport = SteamCdnTransport(client)
        val origin = server(host = "origin.example", type = "CDN")
        val proxy = server(
            host = "proxy.example",
            type = "SteamCache",
            useAsProxy = true,
            proxyTemplate = "/proxy/%host%%path%",
        )

        val bytes = transport.requestBytes(
            server = origin,
            path = "chunk",
            query = null,
            proxyServer = proxy,
            expectedLength = 4L,
        )

        assertContentEquals("data".encodeToByteArray(), bytes)
        assertEquals(listOf("proxy.example", "origin.example"), hosts.toList())
    }

    @Test
    fun rejectsDeclaredOversizeBeforeOpeningResponseBody() = runBlocking {
        val readCalls = AtomicInteger()
        val oversizedBody = object : ResponseBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = 1_024L

            override fun source(): BufferedSource {
                return object : ForwardingSource(Buffer()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        readCalls.incrementAndGet()
                        return super.read(sink, byteCount)
                    }
                }.buffer()
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(oversizedBody)
                        .build()
                },
            )
            .build()

        assertFailsWith<WorkshopDownloadException> {
            SteamCdnTransport(client).requestBytes(
                server = server(host = "origin.example", type = "CDN"),
                path = "chunk",
                query = null,
                proxyServer = null,
                expectedLength = 4L,
            )
        }
        assertEquals(0, readCalls.get())
        Unit
    }

    @Test
    fun passesRejectedTokenToRefreshCallbackAndChecksLength() = runBlocking {
        val queries = CopyOnWriteArrayList<String?>()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    queries += chain.request().url.encodedQuery
                    val call = calls.incrementAndGet()
                    response(chain, if (call == 1) 403 else 200, if (call == 1) "" else "data")
                },
            )
            .build()
        val transport = SteamCdnTransport(client)
        var rejected: String? = null

        val bytes = transport.requestBytes(
            server = server(host = "origin.example", type = "CDN"),
            path = "chunk",
            query = "old-token",
            proxyServer = null,
            expectedLength = 4L,
            resolveRejectedAuthToken = { _, rejectedToken ->
                rejected = rejectedToken
                "new-token"
            },
        )

        assertContentEquals("data".encodeToByteArray(), bytes)
        assertEquals("old-token", rejected)
        assertEquals(listOf<String?>("old-token", "new-token"), queries.toList())

        assertFailsWith<WorkshopDownloadException> {
            transport.requestBytes(
                server = server(host = "origin.example", type = "CDN"),
                path = "chunk",
                query = null,
                proxyServer = null,
                expectedLength = 5L,
            )
        }
        Unit
    }

    @Test
    fun coroutineCancellationCancelsInFlightCall() = runBlocking {
        val entered = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    entered.incrementAndGet()
                    while (!chain.call().isCanceled()) Thread.sleep(5L)
                    throw IOException("cancelled for test")
                },
            )
            .build()
        val transport = SteamCdnTransport(client)
        val job = launch {
            transport.requestBytes(
                server = server(host = "origin.example", type = "CDN"),
                path = "chunk",
                query = null,
                proxyServer = null,
            )
        }
        withTimeout(1_000L) {
            while (entered.get() == 0) delay(5L)
            job.cancelAndJoin()
        }
        assertTrue(job.isCancelled)
    }

    private fun response(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Forbidden")
        .body(body.toResponseBody("application/octet-stream".toMediaType()))
        .build()

    private fun server(
        host: String,
        type: String,
        httpsSupport: String = "mandatory",
        useAsProxy: Boolean = false,
        proxyTemplate: String? = null,
        bypassProxiesOfType: List<String> = emptyList(),
    ) = CdnServer(
        type = type,
        sourceId = 1,
        cellId = 1,
        load = 0,
        weightedLoad = 0f,
        numEntriesInClientList = 1,
        steamChinaOnly = false,
        host = host,
        vHost = host,
        useAsProxy = useAsProxy,
        proxyRequestPathTemplate = proxyTemplate,
        httpsSupport = httpsSupport,
        allowedAppIds = emptyList(),
        priorityClass = 0u,
        bypassProxiesOfType = bypassProxiesOfType,
    )
}
