package top.apricityx.workshop.workshop

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import top.apricityx.workshop.steam.protocol.CdnRequestEndpoint
import top.apricityx.workshop.steam.protocol.CdnServer

internal data class SteamCdnServerPool(
    val proxyServer: CdnServer?,
    val downloadServers: List<CdnServer>,
)

internal enum class SteamCdnRouteKind {
    PROXY,
    ORIGIN,
}

internal data class SteamCdnRequestRoute(
    val kind: SteamCdnRouteKind,
    val endpoint: CdnRequestEndpoint,
    val proxyServer: CdnServer? = null,
)

internal class SteamCdnTransport(
    client: OkHttpClient,
) {
    // SteamPipe can return HTTP-only regional endpoints which redirect to another
    // content host. Keep the caller's timeout policy, but follow Steam CDN redirects
    // just like the desktop Steam clients do. This client is used only for SteamPipe
    // content URLs; Steam Community/API compatibility routing is not applied here.
    private val client = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun buildServerPool(
        appId: UInt,
        contentServers: List<CdnServer>,
    ): SteamCdnServerPool {
        val appServers = contentServers.filter { it.allowedAppIds.isEmpty() || appId in it.allowedAppIds }
        val proxyServer = appServers
            .asSequence()
            .filter(CdnServer::useAsProxy)
            .filter { !it.proxyRequestPathTemplate.isNullOrBlank() }
            .sortedBy(CdnServer::weightedLoad)
            .firstOrNull()
        val eligibleDownloadServers = appServers
            .asSequence()
            .filter { it.type == "SteamCache" || it.type == "CDN" }
            .sortedBy(CdnServer::weightedLoad)
            .toList()
        // Prefer real origin entries so a proxy is not asked to proxy to itself.
        // Some Steam directory responses nevertheless expose only a proxy-flagged
        // CDN/SteamCache entry; retain it as a last-resort candidate instead of
        // producing an empty pool before route-level fallback can run.
        val preferredDownloadServers = eligibleDownloadServers
            .filterNot(CdnServer::useAsProxy)
            .ifEmpty { eligibleDownloadServers }
        val downloadServers = buildList {
            preferredDownloadServers.forEach { server ->
                repeat(server.numEntriesInClientList.coerceAtLeast(0)) {
                    add(server)
                }
            }
        }
        return SteamCdnServerPool(
            proxyServer = proxyServer,
            downloadServers = downloadServers,
        )
    }

    suspend fun requestBytes(
        server: CdnServer,
        path: String,
        query: String?,
        proxyServer: CdnServer?,
        resolveAuthToken: (suspend (String) -> String)? = null,
        expectedLength: Long? = null,
        resolveRejectedAuthToken: (suspend (host: String, rejectedToken: String?) -> String)? = null,
    ): ByteArray {
        require(expectedLength == null || expectedLength >= 0L) { "expectedLength must not be negative" }
        var lastError: Throwable? = null
        val queryState = CdnQueryState(query)
        for (route in buildRequestRoutes(server, proxyServer)) {
            try {
                currentCoroutineContext().ensureActive()
                return requestBytesFromRoute(
                    server = server,
                    route = route,
                    path = path,
                    queryState = queryState,
                    expectedLength = expectedLength,
                    resolveAuthToken = resolveAuthToken,
                    resolveRejectedAuthToken = resolveRejectedAuthToken,
                )
            } catch (error: Throwable) {
                if (error is Error) throw error
                if (error is CancellationException) throw error
                if (error is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                lastError = error
            }
        }
        val detail = lastError?.message?.takeIf(String::isNotBlank)
        throw WorkshopDownloadException(
            detail?.let { "Steam CDN request exhausted routes: $it" } ?: "Steam CDN request exhausted routes",
            lastError,
        )
    }

    internal fun buildRequestRoutes(
        server: CdnServer,
        proxyServer: CdnServer?,
    ): List<SteamCdnRequestRoute> {
        val usableProxy = proxyServer?.takeIf { proxy ->
            proxy.useAsProxy &&
                !proxy.proxyRequestPathTemplate.isNullOrBlank() &&
                server.bypassProxiesOfType.none { it.equals(proxy.type, ignoreCase = true) }
        }
        val proxyRoutes = usableProxy
            ?.requestEndpoints()
            .orEmpty()
            .map { SteamCdnRequestRoute(SteamCdnRouteKind.PROXY, it, usableProxy) }
        val originRoutes = server.requestEndpoints()
            .map { SteamCdnRequestRoute(SteamCdnRouteKind.ORIGIN, it) }

        // A proxy explicitly selected by Steam is the preferred route, even when
        // it is HTTP-only and the origin advertises HTTPS. The previous scheme-first
        // ordering could try a blocked origin before the regional proxy and turn a
        // proxy-to-origin fallback into a long timeout.
        return proxyRoutes + originRoutes
    }

    internal fun buildRequestUrl(
        server: CdnServer,
        route: SteamCdnRequestRoute,
        path: String,
        query: String?,
    ): HttpUrl {
        val normalizedQuery = normalizeQuery(query)
        val originPath = "/${path.trimStart('/')}"
        val targetHost: String
        val targetPath: String
        when (route.kind) {
            SteamCdnRouteKind.ORIGIN -> {
                targetHost = server.vHost
                targetPath = originPath
            }

            SteamCdnRouteKind.PROXY -> {
                val proxy = requireNotNull(route.proxyServer) { "Proxy route requires a proxy server" }
                val template = requireNotNull(proxy.proxyRequestPathTemplate) { "Proxy route requires a path template" }
                targetHost = proxy.vHost
                targetPath = template
                    .replace("%host%", server.vHost)
                    .replace("%path%", originPath)
                    .let { if (it.startsWith('/')) it else "/$it" }
            }
        }

        return HttpUrl.Builder()
            .scheme(route.endpoint.scheme)
            .host(targetHost)
            .port(route.endpoint.port)
            .encodedPath(targetPath)
            .apply {
                if (normalizedQuery != null) encodedQuery(normalizedQuery)
            }
            .build()
    }

    private suspend fun requestBytesFromRoute(
        server: CdnServer,
        route: SteamCdnRequestRoute,
        path: String,
        queryState: CdnQueryState,
        expectedLength: Long?,
        resolveAuthToken: (suspend (String) -> String)?,
        resolveRejectedAuthToken: (suspend (String, String?) -> String)?,
    ): ByteArray {
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val request = Request.Builder()
                .url(buildRequestUrl(server, route, path, queryState.value))
                .build()
            val response = client.newCall(request).awaitResult(expectedLength)
            when {
                response.isSuccessful -> return response.bytes

                response.code == 403 && attempt == 0 &&
                    (resolveRejectedAuthToken != null || resolveAuthToken != null) -> {
                    val rejectedToken = queryState.value
                    queryState.value = if (resolveRejectedAuthToken != null) {
                        resolveRejectedAuthToken(server.host, rejectedToken)
                    } else {
                        resolveAuthToken!!(server.host)
                    }
                }

                else -> throw WorkshopDownloadException("Steam CDN request failed: ${response.code}")
            }
        }
        throw WorkshopDownloadException("Steam CDN request exhausted auth retries")
    }

    private suspend fun Call.awaitResult(expectedLength: Long?): CdnHttpResult =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                CdnHttpResult(
                                    code = response.code,
                                    isSuccessful = response.isSuccessful,
                                    bytes = if (response.isSuccessful) {
                                        readBodyBytes(response, expectedLength)
                                    } else {
                                        ByteArray(0)
                                    },
                                )
                            }
                            if (!continuation.isCancelled) continuation.resume(result)
                        } catch (error: Throwable) {
                            if (!continuation.isCancelled) continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }

    private fun readBodyBytes(response: Response, expectedLength: Long?): ByteArray {
        val body = response.body ?: return if (expectedLength == null || expectedLength == 0L) {
            ByteArray(0)
        } else {
            throw responseLengthMismatch(expectedLength, 0L)
        }
        val declaredLength = body.contentLength()
        val maximumLength = expectedLength ?: MAX_UNBOUNDED_RESPONSE_BYTES
        if (declaredLength > maximumLength) {
            throw responseLengthMismatch(maximumLength, declaredLength, maximum = expectedLength == null)
        }
        if (expectedLength != null && declaredLength >= 0L && declaredLength != expectedLength) {
            throw responseLengthMismatch(expectedLength, declaredLength)
        }

        body.byteStream().use { input ->
            val fixedLength = expectedLength ?: declaredLength.takeIf { it >= 0L }
            if (fixedLength != null) {
                if (fixedLength > Int.MAX_VALUE) {
                    throw WorkshopDownloadException("Steam CDN response is too large to buffer: $fixedLength bytes")
                }
                val bytes = ByteArray(fixedLength.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read == -1) throw responseLengthMismatch(fixedLength, offset.toLong())
                    offset += read
                }
                if (input.read() != -1) {
                    throw responseLengthMismatch(fixedLength, fixedLength + 1L)
                }
                return bytes
            }

            val output = ByteArrayOutputStream(DEFAULT_RESPONSE_BUFFER_BYTES)
            val buffer = ByteArray(DEFAULT_RESPONSE_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read.toLong()
                if (total > maximumLength) {
                    throw responseLengthMismatch(maximumLength, total, maximum = true)
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun responseLengthMismatch(expected: Long, actual: Long, maximum: Boolean = false) =
        WorkshopDownloadException(
            if (maximum) {
                "Steam CDN response exceeded the maximum length: maximum=$expected actual>=$actual"
            } else {
                "Steam CDN response length mismatch: expected=$expected actual=$actual"
            },
        )

    private fun normalizeQuery(query: String?): String? = query
        ?.trim()
        ?.removePrefix("?")
        ?.takeIf(String::isNotBlank)

    private data class CdnQueryState(var value: String?)

    private data class CdnHttpResult(
        val code: Int,
        val isSuccessful: Boolean,
        val bytes: ByteArray,
    )

    private companion object {
        const val MAX_UNBOUNDED_RESPONSE_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_RESPONSE_BUFFER_BYTES = 64 * 1024
    }
}
