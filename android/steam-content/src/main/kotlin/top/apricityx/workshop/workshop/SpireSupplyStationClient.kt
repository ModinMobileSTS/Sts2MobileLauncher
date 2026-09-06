package top.apricityx.workshop.workshop

import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import top.apricityx.workshop.steam.protocol.CdnAuthToken
import top.apricityx.workshop.steam.protocol.CdnServer

/** Native client for the public download protocol; never receives Steam account credentials. */
class SpireSupplyStationClient(private val client: OkHttpClient) {
    private val apiClient =
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun resolve(request: WorkshopDownloadRequest): Download {
        var descriptor = loadDescriptor(request, refresh = false)
        if (!Instant.parse(descriptor.expiresAt).isAfter(Instant.now())) {
            descriptor = loadDescriptor(request, refresh = true)
        }
        require(Instant.parse(descriptor.expiresAt).isAfter(Instant.now())) {
            "Supply station authorization has expired"
        }
        return Download(request, descriptor)
    }

    internal inner class Download(
        private val request: WorkshopDownloadRequest,
        private val original: Descriptor,
    ) {
        private var current = original
        private val refreshLock = Mutex()
        private val tokens = SteamCdnAuthTokenCache()
        private val transport = SteamCdnTransport(client)
        private val endpoints = original.endpoints.associateBy { checkedOrigin(it.origin).host }
        val item: ResolvedWorkshopItem

        init {
            val resolution =
                WorkshopItemResolution(
                    requestedBranch = BRANCH,
                    manifestId = original.manifestId?.toULongOrNull(),
                    depotId = original.depotId,
                    source = SOURCE,
                    fallbackReason = "site_default_content; game_branch_not_verified",
                )
            // Persist identity only, never the key, request code, signed URLs or CDN tokens.
            val metadata =
                buildJsonObject {
                        put("source", SOURCE)
                        put("appId", request.appId.toLong())
                        put("publishedFileId", request.publishedFileId.toString())
                        put("contentVersion", original.contentVersion)
                        put("branch", BRANCH)
                        put("manifestId", original.manifestId)
                        put("depotId", original.depotId?.toLong())
                    }
                    .toString()
            item =
                when (original.mode) {
                    "UGC" -> {
                        require(endpoints.isNotEmpty()) {
                            "Supply station returned no CDN endpoints"
                        }
                        require(original.requestCode?.toULongOrNull() != null) {
                            "Invalid manifest request code"
                        }
                        require(depotKey(original).size == 32) { "Invalid depot key" }
                        ResolvedWorkshopItem.UgcManifestItem(
                            requireNotNull(original.manifestId?.toULongOrNull()) {
                                "Invalid manifest ID"
                            },
                            requireNotNull(original.depotId) { "Missing depot ID" },
                            original.title,
                            metadata,
                            resolution,
                        )
                    }
                    "DIRECT" -> {
                        val url = requireNotNull(original.directUrl).toHttpUrl()
                        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
                            "Unsafe direct download URL"
                        }
                        ResolvedWorkshopItem.DirectUrlItem(
                            original.fileName,
                            url.toString(),
                            original.fileSizeBytes,
                            original.title,
                            metadata,
                            resolution,
                        )
                    }
                    else -> error("Unsupported supply station download mode")
                }
        }

        suspend fun downloadUgc(
            downloader: UgcWorkshopDownloader,
            emit: suspend (DownloadEvent) -> Unit,
            log: suspend (String) -> Unit,
        ) {
            val ugc = item as ResolvedWorkshopItem.UgcManifestItem
            downloader.downloadAuthorized(
                request,
                ugc,
                endpoints.values.map { toServer(it.origin) },
                depotKey(original),
                original.requestCode!!.toULong(),
                ::fetchBytes,
                emit,
                log,
            )
        }

        private suspend fun refreshDescriptor(observed: Descriptor): Descriptor =
            refreshLock.withLock {
                if (current !== observed) return@withLock current
                val replacement = loadDescriptor(request, refresh = true)
                require(
                    replacement.mode == original.mode &&
                        replacement.manifestId == original.manifestId &&
                        replacement.depotId == original.depotId &&
                        replacement.contentVersion == original.contentVersion &&
                        replacement.depotKeyBase64 == original.depotKeyBase64
                ) {
                    "Supply station content changed during download; start a new download"
                }
                require(Instant.parse(replacement.expiresAt).isAfter(Instant.now())) {
                    "Supply station authorization has expired"
                }
                current = replacement
                current
            }

        private suspend fun fetchBytes(
            server: CdnServer,
            path: String,
            expectedLength: Long?,
        ): ByteArray {
            var descriptor = refreshLock.withLock { current }
            if (!Instant.parse(descriptor.expiresAt).isAfter(Instant.now()))
                descriptor = refreshDescriptor(descriptor)
            val manifest = path.startsWith("depot/${original.depotId}/manifest/")
            repeat(2) { attempt ->
                val actualPath =
                    if (manifest) {
                        "depot/${descriptor.depotId}/manifest/${descriptor.manifestId}/5/${descriptor.requestCode}"
                    } else path
                val endpoint =
                    descriptor.endpoints.firstOrNull {
                        checkedOrigin(it.origin).host == server.host
                    } ?: throw IOException("CDN endpoint no longer authorized by supply station")
                try {
                    return transport.requestBytes(
                        server,
                        actualPath,
                        tokens.cached(server.host) ?: endpoint.query,
                        null,
                        expectedLength = expectedLength,
                        resolveAuthToken = { host -> resolveToken(host, null) },
                        resolveRejectedAuthToken = { host, rejected ->
                            resolveToken(host, rejected)
                        },
                    )
                } catch (error: Exception) {
                    if (error is CancellationException || error is InterruptedException) throw error
                    if (!manifest || attempt != 0 || !error.isAuthorizationFailure()) throw error
                    descriptor = refreshDescriptor(descriptor)
                }
            }
            throw IOException("Supply station manifest authorization failed")
        }

        private suspend fun resolveToken(host: String, rejected: String?): String =
            tokens.resolve(host, rejected) {
                val endpoint =
                    requireNotNull(endpoints[host]) { "Unrecognized supply station CDN host" }
                val url =
                    apiUrl("cdn/token", request)
                        .newBuilder()
                        .addQueryParameter("host", host)
                        .apply { if (rejected != null) addQueryParameter("refresh", "true") }
                        .build()
                val response = json.decodeFromString<Token>(getJson(url))
                require(checkedOrigin(response.origin) == checkedOrigin(endpoint.origin)) {
                    "CDN token origin mismatch"
                }
                val query = response.query.trim().removePrefix("?")
                require(query.isNotEmpty() && '\r' !in query && '\n' !in query) {
                    "Invalid CDN token"
                }
                val expiration =
                    checkedOrigin(response.origin)
                        .newBuilder()
                        .encodedQuery(query)
                        .build()
                        .queryParameter("expiration_time")
                        ?.toLongOrNull()
                        ?.let(Instant::ofEpochSecond) ?: Instant.now().plusSeconds(300)
                CdnAuthToken(query, expiration)
            }
    }

    private suspend fun loadDescriptor(
        request: WorkshopDownloadRequest,
        refresh: Boolean,
    ): Descriptor {
        val url =
            apiUrl("descriptor", request)
                .newBuilder()
                .apply { if (refresh) addQueryParameter("refresh", "true") }
                .build()
        val descriptor = json.decodeFromString<Descriptor>(getJson(url))
        require(
            descriptor.appId == request.appId &&
                descriptor.publishedFileId.toULongOrNull() == request.publishedFileId
        ) {
            "Supply station returned a different Workshop item"
        }
        descriptor.endpoints.forEach { checkedOrigin(it.origin) }
        return descriptor
    }

    private fun apiUrl(path: String, request: WorkshopDownloadRequest): HttpUrl =
        "$BASE_URL/api/downloads/browser/$path"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("appId", request.appId.toString())
            .addQueryParameter("publishedFileId", request.publishedFileId.toString())
            .build()

    private suspend fun getJson(url: HttpUrl): String =
        suspendCancellableCoroutine { continuation ->
            val call = apiClient.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled)
                            continuation.resumeWithException(
                                IOException(
                                    "Supply station request failed (${e.javaClass.simpleName})"
                                )
                            )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val text =
                                response.use {
                                    if (!it.isSuccessful)
                                        throw IOException("Supply station HTTP ${it.code}")
                                    val source =
                                        requireNotNull(it.body) { "Empty supply station response" }
                                            .source()
                                    require(!source.request(MAX_JSON_BYTES + 1)) {
                                        "Supply station response is too large"
                                    }
                                    source.readUtf8()
                                }
                            if (!continuation.isCancelled) continuation.resume(text)
                        } catch (error: Exception) {
                            if (!continuation.isCancelled) continuation.resumeWithException(error)
                        }
                    }
                }
            )
        }

    private fun checkedOrigin(value: String): HttpUrl =
        value.toHttpUrl().also {
            require(
                it.isHttps &&
                    it.port == 443 &&
                    it.username.isEmpty() &&
                    it.password.isEmpty() &&
                    it.encodedPath == "/" &&
                    it.query == null &&
                    it.fragment == null
            ) {
                "Invalid supply station CDN origin"
            }
        }

    private fun toServer(origin: String): CdnServer {
        val url = checkedOrigin(origin)
        return CdnServer(
            "CDN",
            0,
            0,
            0,
            0f,
            1,
            false,
            url.host,
            url.host,
            false,
            null,
            "mandatory",
            emptyList(),
            0u,
        )
    }

    private fun depotKey(descriptor: Descriptor): ByteArray =
        Base64.getDecoder()
            .decode(requireNotNull(descriptor.depotKeyBase64) { "Missing depot key" })

    private fun Throwable.isAuthorizationFailure(): Boolean =
        generateSequence(this) { it.cause }
            .any { error ->
                error.message?.let { it.contains(": 401") || it.contains(": 403") } == true
            }

    @Serializable
    internal data class Descriptor(
        val mode: String,
        val appId: UInt,
        val publishedFileId: String,
        val title: String,
        val fileName: String,
        val fileSizeBytes: Long? = null,
        val contentVersion: String,
        val directUrl: String? = null,
        val manifestId: String? = null,
        val depotId: UInt? = null,
        val requestCode: String? = null,
        val endpoints: List<Endpoint> = emptyList(),
        val depotKeyBase64: String? = null,
        val expiresAt: String,
    )

    @Serializable internal data class Endpoint(val origin: String, val query: String? = null)

    @Serializable private data class Token(val origin: String, val query: String)

    companion object {
        const val BASE_URL = "https://workshop.apricityx.top"
        const val SOURCE = "spire_supply_station"
        // This is an installation namespace, NOT a claimed Steam/game branch.
        const val BRANCH = "supply-station-default"
        private const val MAX_JSON_BYTES = 1024L * 1024L
    }
}
