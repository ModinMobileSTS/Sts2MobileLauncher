package com.godot.game.steam.download

import android.content.Context
import com.godot.game.PayloadManager
import com.godot.game.steam.auth.SteamAuthStore
import com.godot.game.steam.core.SteamClientIdentity
import com.godot.game.steam.core.SteamNetworkClientFactory
import com.godot.game.steam.core.SteamSettings
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.util.stream.MemoryStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAppProductInfo
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.workshop.ManifestFile
import top.apricityx.workshop.workshop.PreparedDepotManifest
import top.apricityx.workshop.workshop.SteamDepotDirectoryDownloadProgress
import top.apricityx.workshop.workshop.SteamDepotDirectoryDownloadRequest
import top.apricityx.workshop.workshop.SteamDepotDirectoryDownloader
import top.apricityx.workshop.workshop.SteamDepotManifestRequest

class Sts2SteamPayloadDownloader(private val context: Context) {
    data class Progress(
        val phase: String,
        val percent: Int,
        val message: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    )

    fun downloadAndInstall(
        branch: String,
        listener: ((Progress) -> Unit)? = null,
        control: PayloadManager.ImportControl? = null,
    ): PayloadManager.Status = runBlocking {
        val appContext = context.applicationContext
        val normalizedBranch = branch.trim().ifBlank { DEFAULT_BRANCH }
        val concurrentChunks = SteamSettings.getPayloadConcurrentChunks(appContext)
        val auth = SteamAuthStore.readAuthMaterial(appContext)
            ?: throw IOException("Steam account is not logged in.")
        val steamId = authSteamId(appContext)
        val identity = SteamClientIdentity(appContext)
        val client = SteamNetworkClientFactory.createDefaultClient()
        val directoryClient = SteamDirectoryClient(client)
        val downloader = SteamDepotDirectoryDownloader(
            client = client,
            directoryClient = directoryClient,
            sessionFactory = { identity.createSession(client) },
            sessionConnector = { session, servers ->
                session.connectWithRefreshToken(servers, SteamAccountSession(
                    accountName = auth.accountName,
                    steamId = steamId,
                    refreshToken = auth.refreshToken,
                    machineName = identity.machineName,
                ))
            },
        )
        val account = SteamAccountSession(
            accountName = auth.accountName,
            steamId = steamId,
            refreshToken = auth.refreshToken,
            machineName = identity.machineName,
        )
        emit(listener, Progress("connect", 1, "Connecting to Steam…"))
        control?.throwIfCancelled()
        val cmServers = directoryClient.loadServers()
        var candidates: List<DepotManifestCandidate> = emptyList()
        val manifests = mutableListOf<PreparedCandidate>()
        identity.createSession(client).use { session ->
            session.connectWithRefreshToken(cmServers, account)
            emit(listener, Progress("resolve", 5, "Reading Steam app info…"))
            val appInfo = parseAppInfo(session.requestAppProductInfo(STS2_APP_ID))
            candidates = resolveDepotCandidates(session, STS2_APP_ID, appInfo, normalizedBranch, linkedSetOf())
                .distinctBy { "${it.appId}:${it.depotId}:${it.manifestId}" }
                .sortedWith(compareBy<DepotManifestCandidate> { preferredDepotRank(it.depotId) }.thenBy { it.depotId.toLong() })
            if (candidates.isEmpty()) {
                throw IOException("Steam appinfo did not expose depot manifests for app=$STS2_APP_ID branch=$normalizedBranch")
            }
            emit(listener, Progress("resolve", 8, "Checking ${candidates.size} depot manifest(s)…"))
            for ((index, candidate) in candidates.withIndex()) {
                control?.throwIfCancelled()
                try {
                    val depotKey = session.requestDepotDecryptionKey(candidate.appId, candidate.depotId)
                    val manifest = downloader.loadManifest(
                        request = SteamDepotManifestRequest(
                            appId = candidate.appId,
                            depotId = candidate.depotId,
                            manifestId = candidate.manifestId,
                            branch = normalizedBranch,
                            depotKey = depotKey,
                        ),
                        waitIfPaused = { control?.throwIfCancelled() },
                    )
                    control?.throwIfCancelled()
                    manifests += PreparedCandidate(candidate, depotKey, manifest)
                    val percent = 8 + ((index + 1) * 12 / candidates.size.coerceAtLeast(1))
                    emit(listener, Progress("resolve", percent, "Manifest ${candidate.depotId}: ${manifest.files.size} file(s)"))
                } catch (error: Throwable) {
                    // Some shared depots may be unavailable or unrelated. Keep probing candidates.
                    rethrowIfCancelled(control, error)
                }
            }
        }
        if (manifests.isEmpty()) {
            throw IOException("Unable to download any Steam depot manifest for STS2 branch=$normalizedBranch")
        }
        val selected = selectManifests(manifests)
        val covered = selected.flatMap { prepared -> REQUIRED_PAYLOAD_PATHS.filter { prepared.manifest.containsPath(it) } }.toSet()
        if (!covered.containsAll(REQUIRED_PAYLOAD_PATHS)) {
            throw IOException("Steam depot manifests did not contain all required STS2 payload files. Missing: ${(REQUIRED_PAYLOAD_PATHS - covered).joinToString()}")
        }
        val stagingTask = prepareStagingTask(appContext, buildDownloadFingerprint(normalizedBranch, selected))
        stagingTask.use { lockedTask ->
            val staging = lockedTask.directory
            val totalBytes = selected.sumOf { it.manifest.totalRegularBytes { file -> includePayloadFile(file) } }
            val depotsJson = JSONArray()
            var completedDepotBytes = 0L
            selected.forEach { prepared ->
                control?.throwIfCancelled()
                val depotBytes = prepared.manifest.totalRegularBytes { file -> includePayloadFile(file) }
                val startPercent = downloadPercent(completedDepotBytes, totalBytes)
                emit(
                    listener,
                    Progress(
                        "download",
                        startPercent,
                        "Downloading depot ${prepared.candidate.depotId} with $concurrentChunks chunk worker(s)…",
                        completedDepotBytes,
                        totalBytes,
                    ),
                )
                downloader.download(
                    request = SteamDepotDirectoryDownloadRequest(
                        appId = prepared.candidate.appId,
                        depotId = prepared.candidate.depotId,
                        manifestId = prepared.candidate.manifestId,
                        branch = normalizedBranch,
                        outputRoot = staging,
                        depotKey = prepared.depotKey,
                        includePredicate = { file -> includePayloadFile(file) },
                        preparedManifest = prepared.manifest,
                        maxConcurrentChunks = concurrentChunks,
                    ),
                    emitProgress = { progress ->
                        val globalWritten = (completedDepotBytes + progress.writtenBytes)
                            .coerceIn(0L, totalBytes.coerceAtLeast(0L))
                        emit(
                            listener,
                            progress.toPayloadProgress(
                                percent = downloadPercent(globalWritten, totalBytes),
                                downloadedBytes = globalWritten,
                                totalDownloadBytes = totalBytes,
                            ),
                        )
                    },
                    waitIfPaused = { control?.throwIfCancelled() },
                )
                completedDepotBytes = (completedDepotBytes + depotBytes).coerceAtMost(totalBytes)
                depotsJson.put(JSONObject()
                    .put("app_id", prepared.candidate.appId.toLong())
                    .put("depot_id", prepared.candidate.depotId.toLong())
                    .put("manifest_id", prepared.candidate.manifestId.toString())
                    .put("branch", normalizedBranch)
                    .put("file_count", prepared.manifest.regularFiles().count { includePayloadFile(it) })
                    .put("total_bytes", depotBytes))
            }
            emit(listener, Progress("install", 86, "Installing downloaded payload…"))
            val extras = JSONObject()
                .put("steam", JSONObject()
                    .put("app_id", STS2_APP_ID.toLong())
                    .put("branch", normalizedBranch)
                    .put("concurrent_chunks", concurrentChunks)
                    .put("depots", depotsJson)
                    .put("downloaded_at_unix", System.currentTimeMillis() / 1000L)
                    .put("file_count", countFiles(staging))
                    .put("total_bytes", directorySize(staging)))
            val source = PayloadManager.SourceInfo(
                "steam_depot",
                "Steam App $STS2_APP_ID / $normalizedBranch",
                directorySize(staging),
                "",
                extras,
            )
            PayloadManager(appContext).importPayloadDirectory(staging, source, { percent, stage ->
                emit(listener, Progress("install", 86 + ((percent.coerceIn(0, 100) * 14) / 100), "Installing: $stage"))
            }, control)
        }
    }

    private fun SteamDepotDirectoryDownloadProgress.toPayloadProgress(
        percent: Int,
        downloadedBytes: Long = writtenBytes,
        totalDownloadBytes: Long = totalBytes,
    ): Progress = Progress(
        phase = "download",
        percent = percent.coerceIn(0, 100),
        message = buildString {
            if (currentFile.isBlank()) {
                append("Downloading… ")
            } else {
                append(currentFile)
                append(" · ")
            }
            append(completedFiles)
            append("/")
            append(totalFiles)
            append(" file(s)")
            if (totalChunks > 0) {
                append(" · chunk ")
                append(completedChunks)
                append("/")
                append(totalChunks)
            }
        },
        downloadedBytes = downloadedBytes,
        totalBytes = totalDownloadBytes,
    )

    private fun authSteamId(context: Context): Long {
        val snapshot = SteamAuthStore.readSnapshot(context)
        return snapshot.steamId64.toLongOrNull()?.takeIf { it > 0L } ?: 0L
    }

    private suspend fun resolveDepotCandidates(
        session: top.apricityx.workshop.steam.protocol.SteamCmSession,
        appId: UInt,
        appInfo: KeyValue,
        branch: String,
        visitedAppIds: LinkedHashSet<UInt>,
    ): List<DepotManifestCandidate> {
        if (!visitedAppIds.add(appId)) {
            return emptyList()
        }
        val depots = appInfo.child("depots") ?: return emptyList()
        val candidates = mutableListOf<DepotManifestCandidate>()
        for (depot in depots.children) {
            val depotId = depot.name.trim().toUIntOrNull() ?: continue
            val manifestId = depot.child("manifests")
                ?.child(branch)
                ?.child("gid")
                ?.asManifestId()
            if (manifestId != null) {
                candidates += DepotManifestCandidate(appId, depotId, manifestId, branch)
                continue
            }
            val depotFromApp = depot.child("depotfromapp")?.asAppId()?.takeIf { it != appId } ?: continue
            val parentInfo = parseAppInfo(session.requestAppProductInfo(depotFromApp))
            candidates += resolveDepotCandidates(session, depotFromApp, parentInfo, branch, visitedAppIds)
                .filter { it.depotId == depotId }
        }
        return candidates
    }

    private fun parseAppInfo(productInfo: SteamAppProductInfo): KeyValue {
        val root = KeyValue()
        val bufferSize = productInfo.buffer.size.let { size ->
            if (size > 0 && productInfo.buffer[size - 1] == 0.toByte()) size - 1 else size
        }
        MemoryStream(productInfo.buffer, 0, bufferSize).use { stream ->
            if (!root.readAsText(stream)) {
                throw IOException("Failed to parse Steam appinfo for app=${productInfo.appId}")
            }
        }
        return root
    }

    private fun selectManifests(candidates: List<PreparedCandidate>): List<PreparedCandidate> {
        candidates.firstOrNull { prepared -> REQUIRED_PAYLOAD_PATHS.all { prepared.manifest.containsPath(it) } }?.let { return listOf(it) }
        val remaining = REQUIRED_PAYLOAD_PATHS.toMutableSet()
        val selected = mutableListOf<PreparedCandidate>()
        for (candidate in candidates.sortedByDescending { prepared -> REQUIRED_PAYLOAD_PATHS.count { prepared.manifest.containsPath(it) } }) {
            val covered = remaining.filter { candidate.manifest.containsPath(it) }
            if (covered.isEmpty()) {
                continue
            }
            selected += candidate
            remaining.removeAll(covered.toSet())
            if (remaining.isEmpty()) {
                break
            }
        }
        return selected
    }

    private fun includePayloadFile(file: ManifestFile): Boolean {
        val path = file.path.replace('\\', '/').trim().trimStart('/')
        if (path.isBlank() || path.contains("/../") || path.startsWith("../") || path.contains(':')) {
            return false
        }
        // STS2 PC payload currently needs the root pack/release metadata and the Windows .NET publish tree.
        return path == "SlayTheSpire2.pck" ||
            path == "release_info.json" ||
            path.startsWith("data_sts2_windows_x86_64/")
    }

    private fun buildDownloadFingerprint(
        branch: String,
        selected: List<PreparedCandidate>,
    ): String {
        val identity = buildString {
            append(DOWNLOAD_LAYOUT_VERSION)
            append('|')
            append(branch.trim().lowercase(Locale.ROOT))
            selected.sortedWith(compareBy<PreparedCandidate> { it.candidate.appId }.thenBy { it.candidate.depotId })
                .forEach { prepared ->
                    append('|')
                    append(prepared.candidate.appId)
                    append(':')
                    append(prepared.candidate.depotId)
                    append(':')
                    append(prepared.candidate.manifestId)
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun prepareStagingTask(context: Context, fingerprint: String): LockedStagingTask {
        val root = File(File(context.filesDir, "steam"), "downloads")
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Unable to create Steam downloads directory: ${root.absolutePath}")
        }
        val taskName = "payload-${fingerprint.take(DOWNLOAD_FINGERPRINT_LENGTH)}"
        val lockRoot = File(root, "locks")
        if (!lockRoot.isDirectory && !lockRoot.mkdirs()) {
            throw IOException("Unable to create Steam download lock directory: ${lockRoot.absolutePath}")
        }
        val lockHandle = RandomAccessFile(File(lockRoot, PAYLOAD_DOWNLOAD_LOCK_FILE_NAME), "rw")
        val taskLock = try {
            try {
                lockHandle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
        } catch (error: Throwable) {
            runCatching { lockHandle.close() }
            throw error
        }
        if (taskLock == null) {
            lockHandle.close()
            throw IOException("Another Steam payload download or install is already running.")
        }

        val now = System.currentTimeMillis()
        try {
            root.listFiles()?.forEach { child ->
                if (child.name.startsWith("staging-") || child.name.startsWith("failed-")) {
                    child.deleteRecursively()
                } else if (child.name.startsWith("payload-") &&
                    child.name != taskName &&
                    now - child.lastModified() >= STALE_DOWNLOAD_RETENTION_MILLIS
                ) {
                    child.deleteRecursively()
                }
            }
            val dir = File(root, taskName)
            if (dir.exists() && !dir.isDirectory) {
                throw IOException("Steam payload task path is not a directory: ${dir.absolutePath}")
            }
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("Unable to create Steam payload staging directory: ${dir.absolutePath}")
            }
            dir.setLastModified(now)
            return LockedStagingTask(dir, taskLock, lockHandle)
        } catch (error: Throwable) {
            runCatching { taskLock.release() }
            runCatching { lockHandle.close() }
            throw error
        }
    }

    private class LockedStagingTask(
        val directory: File,
        private val taskLock: FileLock,
        private val lockHandle: RandomAccessFile,
    ) : Closeable {
        override fun close() {
            try {
                taskLock.release()
            } finally {
                lockHandle.close()
            }
        }
    }

    private fun downloadPercent(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return DOWNLOAD_PERCENT_START
        val normalized = downloadedBytes.coerceIn(0L, totalBytes)
        return DOWNLOAD_PERCENT_START + ((normalized * DOWNLOAD_PERCENT_SPAN) / totalBytes).toInt()
    }

    private fun rethrowIfCancelled(control: PayloadManager.ImportControl?, error: Throwable) {
        if (error is Error) throw error
        if (error is CancellationException) throw error
        if (error is InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
        if (control?.isCancelled == true || Thread.currentThread().isInterrupted) {
            throw IOException("Import cancelled.", error)
        }
    }

    private fun countFiles(file: File): Int {
        if (!file.exists()) return 0
        if (file.isFile) return 1
        return file.listFiles()?.sumOf { countFiles(it) } ?: 0
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }

    private fun KeyValue.child(name: String): KeyValue? = get(name).takeIf { it != KeyValue.INVALID }

    private fun KeyValue.asManifestId(): ULong? {
        asString()?.trim()?.toULongOrNull()?.let { return it }
        val value = asLong(0L)
        return value.takeIf { it > 0L }?.toULong()
    }

    private fun KeyValue.asAppId(): UInt? {
        asString()?.trim()?.toUIntOrNull()?.let { return it }
        val value = asInteger(0)
        return value.takeIf { it > 0 }?.toUInt()
    }

    private fun preferredDepotRank(depotId: UInt): Int {
        val index = PREFERRED_DEPOT_IDS.indexOf(depotId)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun emit(listener: ((Progress) -> Unit)?, progress: Progress) {
        listener?.invoke(progress)
    }

    private data class DepotManifestCandidate(
        val appId: UInt,
        val depotId: UInt,
        val manifestId: ULong,
        val branch: String,
    )

    private data class PreparedCandidate(
        val candidate: DepotManifestCandidate,
        val depotKey: ByteArray,
        val manifest: PreparedDepotManifest,
    )

    companion object {
        const val DEFAULT_BRANCH = "public"
        val STS2_APP_ID: UInt = 2868840u
        private const val DOWNLOAD_LAYOUT_VERSION = "steam-payload-v2"
        private const val DOWNLOAD_FINGERPRINT_LENGTH = 24
        private const val DOWNLOAD_PERCENT_START = 20
        private const val DOWNLOAD_PERCENT_SPAN = 62
        private const val PAYLOAD_DOWNLOAD_LOCK_FILE_NAME = "payload-download.lock"
        private const val STALE_DOWNLOAD_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private val REQUIRED_PAYLOAD_PATHS = setOf(
            "SlayTheSpire2.pck",
            "release_info.json",
            "data_sts2_windows_x86_64/sts2.dll",
            "data_sts2_windows_x86_64/sts2.deps.json",
            "data_sts2_windows_x86_64/sts2.runtimeconfig.json",
        )
        // Unknown depots are still considered; this only keeps likely Windows depots first if IDs are known later.
        private val PREFERRED_DEPOT_IDS = listOf(2868841u, 2868842u, 2868840u)
    }
}
