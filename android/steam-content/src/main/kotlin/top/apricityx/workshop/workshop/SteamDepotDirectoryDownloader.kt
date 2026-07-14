package top.apricityx.workshop.workshop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamContentClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient

/**
 * SteamPipe downloader for a whole depot subset.  It intentionally downloads
 * into a caller supplied staging directory and never overwrites a live game
 * payload directly.
 */
data class SteamDepotManifestRequest(
    val appId: UInt,
    val depotId: UInt,
    val manifestId: ULong,
    val branch: String = "public",
    val depotKey: ByteArray,
)

data class SteamDepotDirectoryDownloadRequest(
    val appId: UInt,
    val depotId: UInt,
    val manifestId: ULong,
    val branch: String = "public",
    val outputRoot: File,
    val depotKey: ByteArray,
    val includePredicate: (ManifestFile) -> Boolean = { true },
    val preparedManifest: PreparedDepotManifest? = null,
    val maxConcurrentChunks: Int = 1,
)

data class SteamDepotDirectoryDownloadProgress(
    val phase: String,
    val currentFile: String = "",
    val writtenBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0L) 0 else ((writtenBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
}

data class PreparedDepotManifest(
    val appId: UInt,
    val depotId: UInt,
    val manifestId: ULong,
    val branch: String,
    val createdAtEpochSeconds: Long,
    val files: List<ManifestFile>,
) {
    fun regularFiles(): List<ManifestFile> = files.filter { it.linkTarget.isNullOrBlank() && !requiresDirectory(it) }

    fun containsPath(path: String): Boolean = files.any { it.path.equals(path, ignoreCase = true) }

    fun totalRegularBytes(predicate: (ManifestFile) -> Boolean = { true }): Long = regularFiles()
        .asSequence()
        .filter(predicate)
        .sumOf { it.size.coerceAtLeast(0L) }

    private fun requiresDirectory(file: ManifestFile): Boolean {
        if ((file.flags and DEPOT_FILE_FLAG_DIRECTORY) != 0u) {
            return true
        }
        val prefix = "${file.path.trimEnd('/')}/"
        return files.any { other -> other !== file && other.path.startsWith(prefix) }
    }
}

class SteamDepotDirectoryDownloader(
    private val client: OkHttpClient,
    private val directoryClient: SteamDirectoryClient,
    private val sessionFactory: () -> SteamCmSession,
    private val sessionConnector: suspend (SteamCmSession, List<CmServer>) -> SessionContext,
) {
    suspend fun loadManifest(
        request: SteamDepotManifestRequest,
        waitIfPaused: suspend () -> Unit = {},
    ): PreparedDepotManifest = withContext(Dispatchers.IO) {
        withCancellationPolling(waitIfPaused) {
            waitIfPaused()
            val cmServers = directoryClient.loadServers()
            val cdnTransport = SteamCdnTransport(client)
            sessionFactory().use { session ->
                waitIfPaused()
                sessionConnector(session, cmServers)
                val contentClient = SteamContentClient(session, directoryClient)
                waitIfPaused()
                val manifestRequestCode = contentClient.getManifestRequestCode(
                    appId = request.appId,
                    depotId = request.depotId,
                    manifestId = request.manifestId,
                    branch = request.branch,
                )
                if (manifestRequestCode == 0uL) {
                    throw WorkshopDownloadException(
                        "Steam returned no manifest request code for depot=${request.depotId} manifest=${request.manifestId}",
                    )
                }
                val contentServers = loadContentServers(contentClient)
                require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
                val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
                require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
                val tokenCache = SteamCdnAuthTokenCache()
                val manifest = downloadManifest(
                    appId = request.appId,
                    depotId = request.depotId,
                    manifestId = request.manifestId,
                    branch = request.branch,
                    manifestRequestCode = manifestRequestCode,
                    depotKey = request.depotKey,
                    contentServers = serverPool.downloadServers,
                    proxyServer = serverPool.proxyServer,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = tokenCache,
                    waitIfPaused = waitIfPaused,
                )
                manifest.toPrepared(request.appId, request.branch)
            }
        }
    }

    suspend fun download(
        request: SteamDepotDirectoryDownloadRequest,
        emitProgress: suspend (SteamDepotDirectoryDownloadProgress) -> Unit,
        waitIfPaused: suspend () -> Unit = {},
    ): List<File> = withContext(Dispatchers.IO) {
        withCancellationPolling(waitIfPaused) {
            waitIfPaused()
            val cmServers = directoryClient.loadServers()
            val cdnTransport = SteamCdnTransport(client)
            sessionFactory().use { session ->
                waitIfPaused()
                sessionConnector(session, cmServers)
                val contentClient = SteamContentClient(session, directoryClient)
                waitIfPaused()
                val contentServers = loadContentServers(contentClient)
                require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
                val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
                require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
                val tokenCache = SteamCdnAuthTokenCache()
                val preparedManifest = request.preparedManifest?.also { prepared ->
                    requirePreparedManifestMatches(request, prepared)
                } ?: run {
                    waitIfPaused()
                    val manifestRequestCode = contentClient.getManifestRequestCode(
                        appId = request.appId,
                        depotId = request.depotId,
                        manifestId = request.manifestId,
                        branch = request.branch,
                    )
                    if (manifestRequestCode == 0uL) {
                        throw WorkshopDownloadException(
                            "Steam returned no manifest request code for depot=${request.depotId} manifest=${request.manifestId}",
                        )
                    }
                    downloadManifest(
                        appId = request.appId,
                        depotId = request.depotId,
                        manifestId = request.manifestId,
                        branch = request.branch,
                        manifestRequestCode = manifestRequestCode,
                        depotKey = request.depotKey,
                        contentServers = serverPool.downloadServers,
                        proxyServer = serverPool.proxyServer,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = tokenCache,
                        waitIfPaused = waitIfPaused,
                    ).toPrepared(request.appId, request.branch)
                }
                val files = preparedManifest.regularFiles()
                    .asSequence()
                    .filter(request.includePredicate)
                    .sortedBy { it.path.lowercase(Locale.ROOT) }
                    .toList()
                downloadFiles(
                    appId = request.appId,
                    depotId = request.depotId,
                    depotKey = request.depotKey,
                    outputRoot = request.outputRoot,
                    files = files,
                    maxConcurrentChunks = request.maxConcurrentChunks,
                    contentServers = serverPool.downloadServers,
                    proxyServer = serverPool.proxyServer,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = tokenCache,
                    emitProgress = emitProgress,
                    waitIfPaused = waitIfPaused,
                )
            }
        }
    }

    private suspend fun downloadManifest(
        appId: UInt,
        depotId: UInt,
        manifestId: ULong,
        branch: String,
        manifestRequestCode: ULong,
        depotKey: ByteArray,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        waitIfPaused: suspend () -> Unit,
    ): DepotManifest {
        var lastError: Throwable? = null
        for (server in distinctPhysicalServers(contentServers)) {
            waitIfPaused()
            try {
                val bytes = requestBytes(
                    server = server,
                    proxyServer = proxyServer,
                    path = "depot/$depotId/manifest/$manifestId/5/$manifestRequestCode",
                    query = cdnAuthTokenCache.cached(server.host),
                    appId = appId,
                    depotId = depotId,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = cdnAuthTokenCache,
                )
                val parsed = DepotManifestParser.parse(unzipSingleEntry(bytes))
                return if (parsed.filenamesEncrypted) parsed.decryptFilenames(depotKey) else parsed
            } catch (error: Throwable) {
                rethrowCancellation(error)
                lastError = error
            }
        }
        throw WorkshopDownloadException("Unable to download Steam depot manifest for depot=$depotId manifest=$manifestId branch=$branch", lastError)
    }

    private suspend fun downloadFiles(
        appId: UInt,
        depotId: UInt,
        depotKey: ByteArray,
        outputRoot: File,
        files: List<ManifestFile>,
        maxConcurrentChunks: Int,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        emitProgress: suspend (SteamDepotDirectoryDownloadProgress) -> Unit,
        waitIfPaused: suspend () -> Unit,
    ): List<File> {
        val totalBytes = files.sumOf { it.size.coerceAtLeast(0L) }
        val outputs = mutableListOf<DepotOutputFile>()
        val chunkGroups = linkedMapOf<String, DepotChunkGroup>()
        val outputWriter = DepotOutputWriter(MAX_OPEN_OUTPUT_FILES)
        var completedDestinationBytes = 0L
        var completedFiles = 0

        try {
            for (manifestFile in files) {
                waitIfPaused()
                if (manifestFile.size < 0L) {
                    throw WorkshopDownloadException("Depot manifest reported a negative file size: ${manifestFile.path}")
                }
                val target = resolveSafeOutputFile(outputRoot, manifestFile.path)
                ensureParentDirectory(target)
                val reusable = isReusableCompleteFile(target, manifestFile, waitIfPaused)
                val part = File(target.parentFile, "${target.name}$PART_FILE_SUFFIX")
                val hasResumeData = if (reusable) false else preparePartFile(target, part)
                val output = if (reusable) {
                    completedFiles += 1
                    DepotOutputFile(manifestFile, target, part, reused = true)
                } else {
                    DepotOutputFile(manifestFile, target, part, reused = false)
                }
                outputs += output

                val resumeFile = if (output.reused) null else RandomAccessFile(part, "rw")
                try {
                    for (chunk in manifestFile.chunks.sortedBy(ManifestChunk::offset)) {
                        waitIfPaused()
                        currentCoroutineContext().ensureActive()
                        validateChunkRange(manifestFile, chunk)
                        val group = chunkGroups.getOrPut(chunk.idHex) { DepotChunkGroup(chunk) }
                        requireMatchingChunkDefinition(group.chunk, chunk)
                        val destinationBytes = chunk.uncompressedLength.toLong()
                        if (output.reused ||
                            (hasResumeData && resumeFile != null && validateChunkAtOffset(resumeFile, chunk))
                        ) {
                            completedDestinationBytes = saturatingAddDownloadBytes(
                                completedDestinationBytes,
                                destinationBytes,
                            )
                        } else {
                            group.destinations += DepotChunkDestination(output, chunk.offset)
                            group.pendingProgressBytes = saturatingAddDownloadBytes(
                                group.pendingProgressBytes,
                                destinationBytes,
                            )
                        }
                    }
                } finally {
                    resumeFile?.close()
                }
            }

            val completedChunkGroups = chunkGroups.values.count { it.destinations.isEmpty() }
            val plannedChunks = chunkGroups.values
                .asSequence()
                .filter { it.destinations.isNotEmpty() }
                .map { group ->
                    PlannedChunk(
                        chunk = group.chunk,
                        destinations = group.destinations.toList(),
                        estimatedBytes = estimatedChunkMemoryBytes(group.chunk),
                        progressBytes = group.pendingProgressBytes,
                    )
                }
                .toList()
            val totalChunks = chunkGroups.size
            var currentFile = ""

            emitProgress(
                SteamDepotDirectoryDownloadProgress(
                    phase = "download",
                    writtenBytes = completedDestinationBytes.coerceAtMost(totalBytes),
                    totalBytes = totalBytes,
                    completedFiles = completedFiles,
                    totalFiles = files.size,
                    completedChunks = completedChunkGroups,
                    totalChunks = totalChunks,
                ),
            )

            if (plannedChunks.isNotEmpty()) {
                val workerCount = maxConcurrentChunks.coerceIn(1, MAX_CONCURRENT_CHUNKS)
                runBoundedChunkPipeline(
                        plannedChunks = plannedChunks,
                        options = BoundedChunkPipelineOptions(
                            workerCount = workerCount,
                            resultBufferCapacity = if (workerCount > 1) 1 else 0,
                            maxEstimatedBytesInFlight = chunkMemoryBudgetBytes(),
                        ),
                        fetch = { plan ->
                            waitIfPaused()
                            try {
                                downloadChunkWithRetries(
                                    appId = appId,
                                    depotId = depotId,
                                    depotKey = depotKey,
                                    contentServers = contentServers,
                                    proxyServer = proxyServer,
                                    contentClient = contentClient,
                                    cdnTransport = cdnTransport,
                                    cdnAuthTokenCache = cdnAuthTokenCache,
                                    chunk = plan.chunk,
                                    waitIfPaused = waitIfPaused,
                                )
                            } catch (error: Throwable) {
                                rethrowCancellation(error)
                                throw WorkshopDownloadException(
                                    "Failed to download depot chunk ${plan.chunk.idHex}: ${describeThrowable(error)}",
                                    error,
                                )
                            }
                        },
                        write = { plan, payload ->
                            waitIfPaused()
                            if (payload.size != plan.chunk.uncompressedLength) {
                                throw WorkshopDownloadException(
                                    "Processed depot chunk ${plan.chunk.idHex} has an unexpected length",
                                )
                            }
                            for (destination in plan.destinations) {
                                outputWriter.write(destination, payload)
                                currentFile = destination.output.manifestFile.path
                            }
                        },
                        onProgress = { progress ->
                            emitProgress(
                                SteamDepotDirectoryDownloadProgress(
                                    phase = "download",
                                    currentFile = currentFile,
                                    writtenBytes = saturatingAddDownloadBytes(
                                        completedDestinationBytes,
                                        progress.completedBytes,
                                    ).coerceAtMost(totalBytes),
                                    totalBytes = totalBytes,
                                    completedFiles = completedFiles,
                                    totalFiles = files.size,
                                    completedChunks = completedChunkGroups + progress.completedChunks,
                                    totalChunks = totalChunks,
                                ),
                            )
                        },
                    )
            }

            outputWriter.syncAndClose()

            for (output in outputs) {
                if (output.reused) continue
                waitIfPaused()
                RandomAccessFile(output.part, "rw").use { completedFile ->
                    completedFile.setLength(output.manifestFile.size)
                    completedFile.fd.sync()
                }
                when (val validation = WorkshopFileIntegrityVerifier.assessCancellable(
                    output.part,
                    output.manifestFile,
                    waitIfPaused,
                )) {
                    AssembledFileValidation.Verified -> Unit

                    is AssembledFileValidation.ChunkVerifiedHashMismatch -> throw WorkshopDownloadException(
                        "Downloaded file SHA-1 mismatch for ${output.manifestFile.path} " +
                            "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex})",
                    )

                    is AssembledFileValidation.Invalid -> throw WorkshopDownloadException(
                        "Downloaded file checksum mismatch for ${output.manifestFile.path} " +
                            "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex})",
                    )
                }
                if (output.target.exists() && !output.target.deleteRecursively()) {
                    throw WorkshopDownloadException("Failed to replace incomplete output: ${output.target.absolutePath}")
                }
                if (!output.part.renameTo(output.target)) {
                    throw WorkshopDownloadException("Failed to finalize downloaded file: ${output.target.absolutePath}")
                }
                completedFiles += 1
                emitProgress(
                    SteamDepotDirectoryDownloadProgress(
                        phase = "download",
                        currentFile = output.manifestFile.path,
                        writtenBytes = totalBytes,
                        totalBytes = totalBytes,
                        completedFiles = completedFiles,
                        totalFiles = files.size,
                        completedChunks = totalChunks,
                        totalChunks = totalChunks,
                    ),
                )
            }

            if (files.isEmpty() || completedFiles == files.size) {
                emitProgress(
                    SteamDepotDirectoryDownloadProgress(
                        phase = "download",
                        writtenBytes = totalBytes,
                        totalBytes = totalBytes,
                        completedFiles = files.size,
                        totalFiles = files.size,
                        completedChunks = totalChunks,
                        totalChunks = totalChunks,
                    ),
                )
            }
            return outputs.map(DepotOutputFile::target)
        } finally {
            outputWriter.closeQuietly()
        }
    }

    private suspend fun downloadChunkWithRetries(
        appId: UInt,
        depotId: UInt,
        depotKey: ByteArray,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        chunk: ManifestChunk,
        waitIfPaused: suspend () -> Unit,
    ): ByteArray {
        var lastError: Throwable? = null
        if (contentServers.isEmpty()) {
            throw WorkshopDownloadException("No distinct Steam CDN server is available")
        }
        val weightedOffset = (chunk.idHex.hashCode() and Int.MAX_VALUE) % contentServers.size
        val servers = distinctPhysicalServers(rotateServers(contentServers, weightedOffset))
        for (attempt in 1..MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            waitIfPaused()
            for (server in rotateServers(servers, attempt - 1).take(MAX_CHUNK_SERVER_CANDIDATES)) {
                currentCoroutineContext().ensureActive()
                waitIfPaused()
                try {
                    val raw = requestBytes(
                        server = server,
                        proxyServer = proxyServer,
                        path = "depot/$depotId/chunk/${chunk.idHex}",
                        query = cdnAuthTokenCache.cached(server.host),
                        appId = appId,
                        depotId = depotId,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                        expectedLength = chunk.compressedLength.toLong().takeIf { it > 0L },
                    )
                    return ChunkProcessor.process(raw, chunk, depotKey)
                } catch (error: Throwable) {
                    rethrowCancellation(error)
                    lastError = error
                }
            }
            if (attempt < MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
                delay(CHUNK_RETRY_DELAY_MILLIS * attempt)
            }
        }
        throw WorkshopDownloadException("Failed to download chunk ${chunk.idHex}: ${describeThrowable(lastError)}", lastError)
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
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        expectedLength: Long? = null,
    ): ByteArray = cdnTransport.requestBytes(
        server = server,
        path = path,
        query = query,
        proxyServer = proxyServer,
        expectedLength = expectedLength,
        resolveRejectedAuthToken = { host, rejectedToken ->
            cdnAuthTokenCache.resolve(host, rejectedToken) { tokenHost ->
                contentClient.getCdnAuthToken(appId, depotId, tokenHost)
            }
        },
    )

    private suspend fun loadContentServers(contentClient: SteamContentClient): List<CdnServer> = try {
        contentClient.getServersForSteamPipe()
    } catch (error: Throwable) {
        rethrowCancellation(error)
        directoryClient.loadContentServers()
    }

    private fun requirePreparedManifestMatches(
        request: SteamDepotDirectoryDownloadRequest,
        prepared: PreparedDepotManifest,
    ) {
        if (prepared.appId != request.appId ||
            prepared.depotId != request.depotId ||
            prepared.manifestId != request.manifestId
        ) {
            throw WorkshopDownloadException("Prepared manifest identity does not match the requested Steam depot")
        }
    }

    private fun ensureParentDirectory(target: File) {
        val parent = target.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw WorkshopDownloadException("Failed to create output directory: ${parent.absolutePath}")
        }
    }

    private suspend fun isReusableCompleteFile(
        target: File,
        manifestFile: ManifestFile,
        waitIfPaused: suspend () -> Unit,
    ): Boolean {
        if (!target.isFile || target.length() != manifestFile.size) return false
        return WorkshopFileIntegrityVerifier.assessCancellable(
            target,
            manifestFile,
            waitIfPaused,
        ) is AssembledFileValidation.Verified
    }

    private fun preparePartFile(target: File, part: File): Boolean {
        if (part.isDirectory && !part.deleteRecursively()) {
            throw WorkshopDownloadException("Failed to replace invalid partial directory: ${part.absolutePath}")
        }
        if (target.exists()) {
            if (target.isFile && !part.exists() && target.renameTo(part)) {
                return part.length() > 0L
            }
            if (!target.deleteRecursively()) {
                throw WorkshopDownloadException("Failed to clear incomplete output: ${target.absolutePath}")
            }
        }
        return part.isFile && part.length() > 0L
    }

    private fun validateChunkRange(manifestFile: ManifestFile, chunk: ManifestChunk) {
        val length = chunk.uncompressedLength.toLong()
        if (chunk.offset < 0L || length < 0L || chunk.offset > manifestFile.size - length) {
            throw WorkshopDownloadException(
                "Depot chunk ${chunk.idHex} is outside file bounds for ${manifestFile.path}",
            )
        }
        if (chunk.compressedLength < 0) {
            throw WorkshopDownloadException("Depot chunk ${chunk.idHex} reported a negative compressed length")
        }
    }

    private fun requireMatchingChunkDefinition(expected: ManifestChunk, actual: ManifestChunk) {
        if (!expected.id.contentEquals(actual.id) ||
            expected.checksum != actual.checksum ||
            expected.compressedLength != actual.compressedLength ||
            expected.uncompressedLength != actual.uncompressedLength
        ) {
            throw WorkshopDownloadException("Conflicting depot metadata for chunk ${actual.idHex}")
        }
    }

    private fun validateChunkAtOffset(output: RandomAccessFile, chunk: ManifestChunk): Boolean {
        if (chunk.offset > output.length() - chunk.uncompressedLength.toLong()) return false
        val bytes = ByteArray(chunk.uncompressedLength)
        return try {
            output.seek(chunk.offset)
            output.readFully(bytes)
            steamAdler32(bytes) == chunk.checksum
        } catch (_: IOException) {
            false
        }
    }

    private fun estimatedChunkMemoryBytes(chunk: ManifestChunk): Long {
        val compressed = chunk.compressedLength.coerceAtLeast(0).toLong()
        val uncompressed = chunk.uncompressedLength.coerceAtLeast(0).toLong()
        return (compressed + uncompressed) * CHUNK_MEMORY_ESTIMATE_MULTIPLIER
    }

    private fun chunkMemoryBudgetBytes(): Long {
        val adaptive = Runtime.getRuntime().maxMemory() / CHUNK_MEMORY_HEAP_DIVISOR
        return adaptive.coerceIn(MIN_CHUNK_MEMORY_BUDGET_BYTES, MAX_CHUNK_MEMORY_BUDGET_BYTES)
    }

    private suspend fun <T> withCancellationPolling(
        waitIfPaused: suspend () -> Unit,
        block: suspend () -> T,
    ): T = coroutineScope {
        waitIfPaused()
        val monitor = launch {
            while (isActive) {
                delay(CANCELLATION_POLL_MILLIS)
                waitIfPaused()
            }
        }
        try {
            block()
        } finally {
            monitor.cancel()
        }
    }

    private fun distinctPhysicalServers(servers: List<CdnServer>): List<CdnServer> =
        servers.distinctBy { it.host.trim().lowercase(Locale.ROOT) }

    private fun saturatingAddDownloadBytes(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun rethrowCancellation(error: Throwable) {
        if (error is Error) throw error
        if (error is CancellationException) throw error
        if (error is InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }

    private class DepotOutputFile(
        val manifestFile: ManifestFile,
        val target: File,
        val part: File,
        val reused: Boolean,
    )

    private class DepotChunkGroup(
        val chunk: ManifestChunk,
        val destinations: MutableList<DepotChunkDestination> = mutableListOf(),
        var pendingProgressBytes: Long = 0L,
    )

    private data class DepotChunkDestination(
        val output: DepotOutputFile,
        val offset: Long,
    )

    private class DepotOutputWriter(
        private val maxOpenFiles: Int,
    ) {
        private val openFiles = LinkedHashMap<File, RandomAccessFile>(maxOpenFiles, 0.75f, true)

        fun write(destination: DepotChunkDestination, payload: ByteArray) {
            val target = destination.output.part
            val output = openFiles[target] ?: RandomAccessFile(target, "rw").also { opened ->
                openFiles[target] = opened
                evictIfNeeded()
            }
            output.seek(destination.offset)
            output.write(payload)
        }

        fun syncAndClose() {
            var firstError: IOException? = null
            for (output in openFiles.values) {
                try {
                    output.fd.sync()
                } catch (error: IOException) {
                    if (firstError == null) firstError = error
                } finally {
                    try {
                        output.close()
                    } catch (error: IOException) {
                        if (firstError == null) firstError = error
                    }
                }
            }
            openFiles.clear()
            firstError?.let { throw it }
        }

        fun closeQuietly() {
            openFiles.values.forEach { output -> runCatching { output.close() } }
            openFiles.clear()
        }

        private fun evictIfNeeded() {
            while (openFiles.size > maxOpenFiles) {
                val iterator = openFiles.entries.iterator()
                val eldest = iterator.next()
                eldest.value.fd.sync()
                eldest.value.close()
                iterator.remove()
            }
        }
    }

    private fun DepotManifest.toPrepared(appId: UInt, branch: String): PreparedDepotManifest = PreparedDepotManifest(
        appId = appId,
        depotId = depotId,
        manifestId = manifestId,
        branch = branch,
        createdAtEpochSeconds = createdAt.epochSecond,
        files = files,
    )

    private fun unzipSingleEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            zip.nextEntry ?: throw WorkshopDownloadException("Zip payload was empty")
            val output = ByteArrayOutputStream()
            zip.copyTo(output)
            zip.closeEntry()
            return output.toByteArray()
        }
    }

    private fun rotateServers(servers: List<CdnServer>, offset: Int): List<CdnServer> {
        if (servers.isEmpty()) return emptyList()
        return List(servers.size) { index -> servers[(index + offset) % servers.size] }
    }

    private fun resolveSafeOutputFile(root: File, rawPath: String): File {
        val normalized = rawPath.replace('\\', '/').trim().trimStart('/')
        if (normalized.isBlank() || normalized == "." || normalized == ".." || normalized.contains(':')) {
            throw WorkshopDownloadException("Blocked invalid depot path: $rawPath")
        }
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw WorkshopDownloadException("Blocked invalid depot path: $rawPath")
        }
        val output = File(root, segments.joinToString(File.separator))
        try {
            val rootPath = root.canonicalPath
            val outputPath = output.canonicalPath
            if (outputPath != rootPath && !outputPath.startsWith(rootPath + File.separator)) {
                throw WorkshopDownloadException("Blocked depot path outside output root: $rawPath")
            }
        } catch (error: IOException) {
            throw WorkshopDownloadException("Unable to validate depot path: $rawPath", error)
        }
        return output
    }

    private fun describeThrowable(error: Throwable?): String {
        if (error == null) {
            return "unknown error"
        }
        val seen = LinkedHashSet<String>()
        var current: Throwable? = error
        while (current != null && seen.size < 6) {
            val text = buildString {
                append(current!!::class.java.simpleName)
                current!!.message?.trim()?.takeIf(String::isNotBlank)?.let { message ->
                    append(": ")
                    append(message)
                }
            }
            seen += text
            val next = current.cause
            if (next == current) {
                break
            }
            current = next
        }
        return seen.joinToString(" <- ")
    }

    private companion object {
        private const val MAX_CHUNK_DOWNLOAD_ATTEMPTS = 3
        private const val MAX_CHUNK_SERVER_CANDIDATES = 8
        private const val MAX_CONCURRENT_CHUNKS = 8
        private const val MAX_OPEN_OUTPUT_FILES = 8
        private const val CHUNK_RETRY_DELAY_MILLIS = 750L
        private const val CANCELLATION_POLL_MILLIS = 150L
        private const val CHUNK_MEMORY_HEAP_DIVISOR = 12L
        private const val CHUNK_MEMORY_ESTIMATE_MULTIPLIER = 2L
        private const val MIN_CHUNK_MEMORY_BUDGET_BYTES = 16L * 1024L * 1024L
        private const val MAX_CHUNK_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L
        private const val PART_FILE_SUFFIX = ".steam.part"
    }
}
