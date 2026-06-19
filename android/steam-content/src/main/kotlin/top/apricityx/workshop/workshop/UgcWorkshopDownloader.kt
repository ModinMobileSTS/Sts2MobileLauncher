package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamContentClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

class UgcWorkshopDownloader(
    private val client: OkHttpClient,
    private val directoryClient: SteamDirectoryClient,
    private val maxConcurrentChunks: Int = DEFAULT_MAX_CONCURRENT_CHUNKS,
    private val sessionFactory: () -> SteamCmSession = { OkHttpSteamCmSession(client) },
    private val sessionConnector: suspend (SteamCmSession, List<top.apricityx.workshop.steam.protocol.CmServer>) -> top.apricityx.workshop.steam.protocol.SessionContext =
        { session, servers -> session.connectAnonymous(servers) },
    private val allowPublicCdnFallbackOnSessionFailure: Boolean = true,
) {
    suspend fun download(
        request: WorkshopDownloadRequest,
        item: ResolvedWorkshopItem.UgcManifestItem,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        log("Loading Steam CM websocket candidates")
        val cmServers = directoryClient.loadServers()
        log("Loaded ${cmServers.size} CM websocket candidates")
        val cdnTransport = SteamCdnTransport(client)

        sessionFactory().use { session ->
            val contentClient = SteamContentClient(session, directoryClient)
            val connectResult = runCatching { sessionConnector(session, cmServers) }
            connectResult
                .onSuccess { log("Connected to Steam CM cell=${it.cellId} steamId=${it.steamId}") }
                .onFailure {
                    if (allowPublicCdnFallbackOnSessionFailure) {
                        log("Steam CM connection failed, continuing with public CDN flow: ${it.message}")
                    } else {
                        throw it
                    }
                }

            val manifestRequestCode = runCatching {
                contentClient.getManifestRequestCode(
                    appId = request.appId,
                    depotId = item.depotId,
                    manifestId = item.manifestId,
                )
            }.getOrElse {
                log("Manifest request code unavailable, retrying without request code: ${it.message}")
                0uL
            }

            val contentServers = runCatching {
                contentClient.getServersForSteamPipe()
            }.getOrElse {
                log("Falling back to public content server directory API")
                directoryClient.loadContentServers()
            }

            require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
            log("Loaded ${contentServers.size} SteamPipe content servers")
            val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
            require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
            log(
                "Selected ${serverPool.downloadServers.size} weighted CDN entries " +
                    "from ${serverPool.downloadServers.distinctBy(CdnServer::host).size} servers",
            )
            serverPool.proxyServer?.let { proxy ->
                log("Detected CDN proxy host=${proxy.host} template=${proxy.proxyRequestPathTemplate ?: "(none)"}")
            }
            val cdnAuthTokenCache = ConcurrentHashMap<String, String>()

            val depotKey = runCatching {
                session.requestDepotDecryptionKey(
                    appId = request.appId,
                    depotId = item.depotId,
                )
            }.onSuccess {
                log("Loaded depot key for depot=${item.depotId}")
            }.onFailure {
                log("Depot key request failed for depot=${item.depotId}: ${it.message}")
            }.getOrNull()

            val manifest = downloadManifest(
                appId = request.appId,
                item = item,
                contentServers = serverPool.downloadServers,
                proxyServer = serverPool.proxyServer,
                manifestRequestCode = manifestRequestCode,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                log = log,
            )
            val preparedManifest = when {
                manifest.filenamesEncrypted && depotKey != null -> {
                    log("Decrypting encrypted manifest filenames with depot key")
                    runCatching { manifest.decryptFilenames(depotKey) }
                        .getOrElse {
                            log("Manifest filename decryption failed; continuing with encoded names: ${it.message}")
                            manifest
                        }
                }

                manifest.filenamesEncrypted -> {
                    log("Manifest filenames are encrypted but depot key is unavailable; continuing with encoded names")
                    manifest
                }

                else -> manifest
            }

            emit(DownloadEvent.StateChanged(DownloadState.Downloading))
            val chunks = preparedManifest.uniqueChunks()
            val totalBytes = chunks.sumOf { it.uncompressedLength.toLong() }
            val totalFiles = preparedManifest.files.count {
                it.linkTarget.isNullOrBlank() && !preparedManifest.requiresDirectory(it)
            }
            log("Manifest ${preparedManifest.manifestId} contains ${preparedManifest.files.size} files and ${chunks.size} unique chunks")
            emit(
                DownloadEvent.Progress(
                    writtenBytes = 0L,
                    totalBytes = totalBytes,
                    completedChunks = 0,
                    totalChunks = chunks.size,
                    completedFiles = 0,
                    totalFiles = totalFiles,
                ),
            )

            val stageDir = File(request.outputDir, ".chunks").apply { mkdirs() }
            cacheChunks(
                appId = request.appId,
                depotId = item.depotId,
                contentServers = serverPool.downloadServers,
                proxyServer = serverPool.proxyServer,
                contentClient = contentClient,
                cdnTransport = cdnTransport,
                cdnAuthTokenCache = cdnAuthTokenCache,
                chunks = chunks,
                stageDir = stageDir,
                depotKey = depotKey,
                totalFiles = totalFiles,
                emit = emit,
                log = log,
            )

            assembleFiles(
                manifest = preparedManifest,
                outputDir = request.outputDir,
                stageDir = stageDir,
                totalBytes = totalBytes,
                totalChunks = chunks.size,
                totalFiles = totalFiles,
                emit = emit,
                log = log,
            )
        }
    }

    private suspend fun downloadManifest(
        appId: UInt,
        item: ResolvedWorkshopItem.UgcManifestItem,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        manifestRequestCode: ULong,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        log: suspend (String) -> Unit,
    ): DepotManifest {
        var lastError: Throwable? = null
        for (server in contentServers) {
            try {
                log("Trying manifest download from ${server.host}")
                val path = buildString {
                    append("depot/${item.depotId}/manifest/${item.manifestId}/5")
                    if (manifestRequestCode > 0uL) {
                        append("/$manifestRequestCode")
                    }
                }
                val bytes = requestBytes(
                    server = server,
                    proxyServer = proxyServer,
                    path = path,
                    query = cdnAuthTokenCache[server.host],
                    appId = appId,
                    depotId = item.depotId,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = cdnAuthTokenCache,
                )
                return DepotManifestParser.parse(unzipSingleEntry(bytes))
            } catch (error: Throwable) {
                lastError = error
                log("Manifest download failed from ${server.host}: ${error.message}")
            }
        }
        throw WorkshopDownloadException("Unable to download UGC manifest", lastError)
    }

    private suspend fun cacheChunks(
        appId: UInt,
        depotId: UInt,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        chunks: List<ManifestChunk>,
        stageDir: File,
        depotKey: ByteArray?,
        totalFiles: Int,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) = coroutineScope {
        if (chunks.isEmpty()) {
            return@coroutineScope
        }
        val workerCount = maxConcurrentChunks.coerceIn(1, chunks.size)
        val totalBytes = chunks.sumOf { it.uncompressedLength.toLong() }
        val downloaded = AtomicLong(0L)
        val completedChunks = AtomicInteger(0)
        val totalChunks = chunks.size
        val nextChunkIndex = AtomicInteger(0)
        val progress = CoalescedProgressEmitter(
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            totalFiles = totalFiles,
            eventSink = emit,
        )

        List(workerCount) {
            async(Dispatchers.IO) {
                while (true) {
                    val index = nextChunkIndex.getAndIncrement()
                    if (index >= chunks.size) {
                        break
                    }
                    val chunk = chunks[index]
                    val stageFile = File(stageDir, "${chunk.idHex}.chunk")
                    if (tryReuseCachedChunk(stageFile, chunk, downloaded, completedChunks, progress)) {
                        continue
                    }

                    val processed = downloadChunkWithRetries(
                        appId = appId,
                        depotId = depotId,
                        contentServers = contentServers,
                        proxyServer = proxyServer,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                        chunk = chunk,
                        depotKey = depotKey,
                        log = log,
                    )
                    writeAtomically(stageFile, processed)
                    val written = downloaded.addAndGet(processed.size.toLong())
                    val done = completedChunks.incrementAndGet()
                    progress.emit(
                        writtenBytes = written,
                        completedChunks = done,
                        completedFiles = 0,
                        force = done >= totalChunks,
                    )
                }
            }
        }.awaitAll()
    }

    private suspend fun tryReuseCachedChunk(
        stageFile: File,
        chunk: ManifestChunk,
        downloaded: AtomicLong,
        completedChunks: AtomicInteger,
        progress: CoalescedProgressEmitter,
    ): Boolean {
        if (!stageFile.exists()) {
            return false
        }

        if (!validateChunkFile(stageFile, chunk)) {
            stageFile.delete()
            return false
        }

        val written = downloaded.addAndGet(stageFile.length())
        val done = completedChunks.incrementAndGet()
        progress.emit(
            writtenBytes = written,
            completedChunks = done,
            completedFiles = 0,
            force = false,
        )
        return true
    }

    private suspend fun downloadChunkWithRetries(
        appId: UInt,
        depotId: UInt,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        chunk: ManifestChunk,
        depotKey: ByteArray?,
        log: suspend (String) -> Unit,
    ): ByteArray {
        var lastError: Throwable? = null

        for (attempt in 1..MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
            for (server in rotateServers(contentServers, attempt - 1)) {
                try {
                    val path = "depot/$depotId/chunk/${chunk.idHex}"
                    val raw = requestBytes(
                        server = server,
                        proxyServer = proxyServer,
                        path = path,
                        query = cdnAuthTokenCache[server.host],
                        appId = appId,
                        depotId = depotId,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                    )
                    return ChunkProcessor.process(raw, chunk, depotKey)
                } catch (error: Throwable) {
                    lastError = error
                    log("Chunk ${chunk.idHex} failed from ${server.host}: ${error.message}")
                }
            }

            if (attempt < MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
                log("Retrying chunk ${chunk.idHex} (${attempt + 1}/$MAX_CHUNK_DOWNLOAD_ATTEMPTS)")
                delay(CHUNK_RETRY_DELAY_MILLIS * attempt)
            }
        }

        throw WorkshopDownloadException("Failed to download chunk ${chunk.idHex}", lastError)
    }

    private suspend fun assembleFiles(
        manifest: DepotManifest,
        outputDir: File,
        stageDir: File,
        totalBytes: Long,
        totalChunks: Int,
        totalFiles: Int,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) {
        var completedFiles = 0
        val progress = CoalescedProgressEmitter(
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            totalFiles = totalFiles,
            eventSink = emit,
        )
        manifest.files.forEach { file ->
            if (!file.linkTarget.isNullOrBlank()) {
                log("Skipping symlink-like manifest entry ${file.path} -> ${file.linkTarget}")
                return@forEach
            }

            val preparedEntry = WorkshopOutputPathManager.prepare(
                outputDir = outputDir,
                manifest = manifest,
                file = file,
            )
            if (preparedEntry is PreparedManifestEntry.DirectoryEntry) {
                log("Created directory entry ${file.path}")
                return@forEach
            }
            val target = (preparedEntry as PreparedManifestEntry.FileEntry).target

            val existingValidation = target
                .takeIf { it.exists() && it.length() == file.size }
                ?.let { WorkshopFileIntegrityVerifier.assess(it, file) }
            val reuse = existingValidation != null && existingValidation !is AssembledFileValidation.Invalid
            if (existingValidation is AssembledFileValidation.ChunkVerifiedHashMismatch) {
                log(
                    "Reusing ${file.path} despite SHA-1 mismatch because all chunk ranges validated " +
                        "expected=${existingValidation.expectedShaHex} actual=${existingValidation.actualShaHex}",
                )
            }
            if (!reuse) {
                RandomAccessFile(target, "rw").use { output ->
                    output.setLength(file.size)
                    file.chunks.forEach { chunk ->
                        val chunkFile = File(stageDir, "${chunk.idHex}.chunk")
                        output.seek(chunk.offset)
                        chunkFile.inputStream().buffered(IO_BUFFER_SIZE).use { input ->
                            val buffer = ByteArray(IO_BUFFER_SIZE)
                            var bytesSinceYield = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) {
                                    break
                                }
                                output.write(buffer, 0, read)
                                bytesSinceYield += read
                                if (bytesSinceYield >= IO_YIELD_BYTES) {
                                    yield()
                                    bytesSinceYield = 0L
                                }
                            }
                        }
                    }
                }

                when (val validation = WorkshopFileIntegrityVerifier.assess(target, file)) {
                    AssembledFileValidation.Verified -> Unit

                    is AssembledFileValidation.ChunkVerifiedHashMismatch -> {
                        log(
                            "Assembled ${file.path} with valid chunk coverage, but manifest SHA-1 differed; " +
                                "continuing expected=${validation.expectedShaHex} actual=${validation.actualShaHex}",
                        )
                    }

                    is AssembledFileValidation.Invalid -> {
                        throw WorkshopDownloadException(
                            "Assembled file checksum mismatch for ${file.path} " +
                                "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex} " +
                                "exactChunkCoverage=${validation.exactChunkCoverage} " +
                                "chunkChecksumsValid=${validation.chunkChecksumsValid})",
                        )
                    }
                }
            }

            completedFiles += 1
            progress.emit(
                writtenBytes = totalBytes,
                completedChunks = totalChunks,
                completedFiles = completedFiles,
                force = completedFiles >= totalFiles,
            )
        }
    }

    private suspend fun requestBytes(
        server: CdnServer,
        proxyServer: CdnServer?,
        path: String,
        query: String?,
        appId: UInt,
        depotId: UInt,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
    ): ByteArray {
        return cdnTransport.requestBytes(
            server = server,
            path = path,
            query = query,
            proxyServer = proxyServer,
            resolveAuthToken = { host ->
                cdnAuthTokenCache[host] ?: contentClient.getCdnAuthToken(appId, depotId, host).token.also {
                    cdnAuthTokenCache[host] = it
                }
            },
        )
    }

    private fun unzipSingleEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val entry = zip.nextEntry ?: throw WorkshopDownloadException("Zip payload was empty")
            val output = ByteArrayOutputStream()
            zip.copyTo(output)
            zip.closeEntry()
            return output.toByteArray()
        }
    }

    private fun validateChunkFile(file: File, chunk: ManifestChunk): Boolean {
        if (!file.isFile || file.length() != chunk.uncompressedLength.toLong()) {
            return false
        }
        file.inputStream().buffered().use { input ->
            val checksum = steamAdler32(input)
            return checksum == chunk.checksum
        }
    }

    private suspend fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().buffered(IO_BUFFER_SIZE).use { output ->
            var offset = 0
            var bytesSinceYield = 0L
            while (offset < bytes.size) {
                val count = minOf(IO_BUFFER_SIZE, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
                bytesSinceYield += count
                if (bytesSinceYield >= IO_YIELD_BYTES) {
                    yield()
                    bytesSinceYield = 0L
                }
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun rotateServers(
        servers: List<CdnServer>,
        offset: Int,
    ): List<CdnServer> {
        if (servers.isEmpty()) {
            return emptyList()
        }
        return List(servers.size) { index ->
            servers[(index + offset) % servers.size]
        }
    }

    private class CoalescedProgressEmitter(
        private val totalBytes: Long,
        private val totalChunks: Int,
        private val totalFiles: Int,
        private val eventSink: suspend (DownloadEvent) -> Unit,
    ) {
        private val mutex = Mutex()
        private var lastEmittedAtMs = 0L
        private var lastWrittenBytes = -1L
        private var lastCompletedChunks = -1
        private var lastCompletedFiles = -1

        suspend fun emit(
            writtenBytes: Long,
            completedChunks: Int,
            completedFiles: Int,
            force: Boolean = false,
        ) {
            val event = mutex.withLock {
                val now = System.currentTimeMillis()
                val byteDelta = if (lastWrittenBytes < 0L) Long.MAX_VALUE else (writtenBytes - lastWrittenBytes).coerceAtLeast(0L)
                val chunkDelta = completedChunks - lastCompletedChunks
                val fileDelta = completedFiles - lastCompletedFiles
                val shouldEmit = force ||
                    byteDelta >= PROGRESS_EMIT_BYTES ||
                    chunkDelta >= PROGRESS_EMIT_CHUNKS ||
                    fileDelta >= PROGRESS_EMIT_FILES ||
                    now - lastEmittedAtMs >= PROGRESS_EMIT_INTERVAL_MS
                if (!shouldEmit) {
                    null
                } else {
                    lastEmittedAtMs = now
                    lastWrittenBytes = writtenBytes
                    lastCompletedChunks = completedChunks
                    lastCompletedFiles = completedFiles
                    DownloadEvent.Progress(
                        writtenBytes = writtenBytes,
                        totalBytes = totalBytes,
                        completedChunks = completedChunks,
                        totalChunks = totalChunks,
                        completedFiles = completedFiles,
                        totalFiles = totalFiles,
                    )
                }
            }
            if (event != null) {
                eventSink(event)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_CHUNKS = 4
        private const val MAX_CHUNK_DOWNLOAD_ATTEMPTS = 3
        private const val CHUNK_RETRY_DELAY_MILLIS = 750L
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val IO_YIELD_BYTES = 1024 * 1024L
        private const val PROGRESS_EMIT_BYTES = 512 * 1024L
        private const val PROGRESS_EMIT_CHUNKS = 4
        private const val PROGRESS_EMIT_FILES = 8
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    }
}
