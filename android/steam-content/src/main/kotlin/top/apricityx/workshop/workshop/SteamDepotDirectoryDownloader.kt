package top.apricityx.workshop.workshop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    suspend fun loadManifest(request: SteamDepotManifestRequest): PreparedDepotManifest = withContext(Dispatchers.IO) {
        val cmServers = directoryClient.loadServers()
        val cdnTransport = SteamCdnTransport(client)
        sessionFactory().use { session ->
            sessionConnector(session, cmServers)
            val contentClient = SteamContentClient(session, directoryClient)
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
            val contentServers = runCatching { contentClient.getServersForSteamPipe() }
                .getOrElse { directoryClient.loadContentServers() }
            require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
            val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
            require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
            val tokenCache = ConcurrentHashMap<String, String>()
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
                waitIfPaused = {},
            )
            manifest.toPrepared(request.appId, request.branch)
        }
    }

    suspend fun download(
        request: SteamDepotDirectoryDownloadRequest,
        emitProgress: suspend (SteamDepotDirectoryDownloadProgress) -> Unit,
        waitIfPaused: suspend () -> Unit = {},
    ): List<File> = withContext(Dispatchers.IO) {
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
            val contentServers = runCatching { contentClient.getServersForSteamPipe() }
                .getOrElse { directoryClient.loadContentServers() }
            require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
            val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
            require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
            val tokenCache = ConcurrentHashMap<String, String>()
            waitIfPaused()
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
            val files = manifest.files
                .asSequence()
                .filter { it.linkTarget.isNullOrBlank() }
                .filter { !manifest.requiresDirectory(it) }
                .filter(request.includePredicate)
                .sortedBy { it.path.lowercase() }
                .toList()
            val totalBytes = files.sumOf { it.size.coerceAtLeast(0L) }
            var writtenBytes = 0L
            val outputFiles = mutableListOf<File>()
            emitProgress(
                SteamDepotDirectoryDownloadProgress(
                    phase = "download",
                    writtenBytes = 0L,
                    totalBytes = totalBytes,
                    totalFiles = files.size,
                ),
            )
            files.forEachIndexed { index, manifestFile ->
                waitIfPaused()
                val target = resolveSafeOutputFile(request.outputRoot, manifestFile.path)
                val parent = target.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw WorkshopDownloadException("Failed to create output directory: ${parent.absolutePath}")
                }
                if (target.isDirectory && !target.deleteRecursively()) {
                    throw WorkshopDownloadException("Failed to replace directory with file: ${target.absolutePath}")
                }
                val beforeFileBytes = writtenBytes
                downloadFileChunks(
                    appId = request.appId,
                    depotId = request.depotId,
                    depotKey = request.depotKey,
                    outputFile = target,
                    manifestFile = manifestFile,
                    contentServers = serverPool.downloadServers,
                    proxyServer = serverPool.proxyServer,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = tokenCache,
                    emitProgress = { completedChunks, totalChunks, fileWritten ->
                        emitProgress(
                            SteamDepotDirectoryDownloadProgress(
                                phase = "download",
                                currentFile = manifestFile.path,
                                writtenBytes = beforeFileBytes + fileWritten,
                                totalBytes = totalBytes,
                                completedFiles = index,
                                totalFiles = files.size,
                                completedChunks = completedChunks,
                                totalChunks = totalChunks,
                            ),
                        )
                    },
                    waitIfPaused = waitIfPaused,
                )
                writtenBytes += manifestFile.size.coerceAtLeast(0L)
                outputFiles += target
                emitProgress(
                    SteamDepotDirectoryDownloadProgress(
                        phase = "download",
                        currentFile = manifestFile.path,
                        writtenBytes = writtenBytes,
                        totalBytes = totalBytes,
                        completedFiles = index + 1,
                        totalFiles = files.size,
                    ),
                )
            }
            outputFiles
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
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        waitIfPaused: suspend () -> Unit,
    ): DepotManifest {
        var lastError: Throwable? = null
        for (server in contentServers) {
            try {
                waitIfPaused()
                val bytes = requestBytes(
                    server = server,
                    proxyServer = proxyServer,
                    path = "depot/$depotId/manifest/$manifestId/5/$manifestRequestCode",
                    query = cdnAuthTokenCache[server.host],
                    appId = appId,
                    depotId = depotId,
                    contentClient = contentClient,
                    cdnTransport = cdnTransport,
                    cdnAuthTokenCache = cdnAuthTokenCache,
                )
                val parsed = DepotManifestParser.parse(unzipSingleEntry(bytes))
                return if (parsed.filenamesEncrypted) parsed.decryptFilenames(depotKey) else parsed
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw WorkshopDownloadException("Unable to download Steam depot manifest for depot=$depotId manifest=$manifestId branch=$branch", lastError)
    }

    private suspend fun downloadFileChunks(
        appId: UInt,
        depotId: UInt,
        depotKey: ByteArray,
        outputFile: File,
        manifestFile: ManifestFile,
        contentServers: List<CdnServer>,
        proxyServer: CdnServer?,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        emitProgress: suspend (completedChunks: Int, totalChunks: Int, fileWrittenBytes: Long) -> Unit,
        waitIfPaused: suspend () -> Unit,
    ) {
        val chunks = manifestFile.chunks.sortedBy(ManifestChunk::offset)
        var fileWritten = 0L
        emitProgress(0, chunks.size, 0L)
        RandomAccessFile(outputFile, "rw").use { output ->
            output.setLength(manifestFile.size)
            for ((index, chunk) in chunks.withIndex()) {
                waitIfPaused()
                emitProgress(index, chunks.size, fileWritten)
                val processed = try {
                    downloadChunkWithRetries(
                        appId = appId,
                        depotId = depotId,
                        depotKey = depotKey,
                        contentServers = contentServers,
                        proxyServer = proxyServer,
                        contentClient = contentClient,
                        cdnTransport = cdnTransport,
                        cdnAuthTokenCache = cdnAuthTokenCache,
                        chunk = chunk,
                        waitIfPaused = waitIfPaused,
                    )
                } catch (error: Throwable) {
                    throw WorkshopDownloadException(
                        "Failed to download ${manifestFile.path} chunk ${index + 1}/${chunks.size} (${chunk.idHex}): ${describeThrowable(error)}",
                        error,
                    )
                }
                output.seek(chunk.offset)
                output.write(processed)
                fileWritten += processed.size.toLong()
                emitProgress(index + 1, chunks.size, fileWritten)
            }
        }
        when (val validation = WorkshopFileIntegrityVerifier.assess(outputFile, manifestFile)) {
            AssembledFileValidation.Verified,
            is AssembledFileValidation.ChunkVerifiedHashMismatch -> Unit
            is AssembledFileValidation.Invalid -> throw WorkshopDownloadException(
                "Downloaded file checksum mismatch for ${manifestFile.path} " +
                    "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex})",
            )
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
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
        chunk: ManifestChunk,
        waitIfPaused: suspend () -> Unit,
    ): ByteArray {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_CHUNK_DOWNLOAD_ATTEMPTS) {
            for (server in rotateServers(contentServers, attempt - 1).take(MAX_CHUNK_SERVER_CANDIDATES)) {
                try {
                    waitIfPaused()
                    val raw = requestBytes(
                        server = server,
                        proxyServer = proxyServer,
                        path = "depot/$depotId/chunk/${chunk.idHex}",
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
        cdnAuthTokenCache: ConcurrentHashMap<String, String>,
    ): ByteArray = cdnTransport.requestBytes(
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
        private const val CHUNK_RETRY_DELAY_MILLIS = 750L
    }
}
