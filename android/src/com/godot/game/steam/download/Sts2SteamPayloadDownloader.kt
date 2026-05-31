package com.godot.game.steam.download

import android.content.Context
import com.godot.game.PayloadManager
import com.godot.game.steam.auth.SteamAuthStore
import com.godot.game.steam.core.SteamClientIdentity
import com.godot.game.steam.core.SteamNetworkClientFactory
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.util.stream.MemoryStream
import java.io.File
import java.io.IOException
import java.util.UUID
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
                    val manifest = downloader.loadManifest(SteamDepotManifestRequest(
                        appId = candidate.appId,
                        depotId = candidate.depotId,
                        manifestId = candidate.manifestId,
                        branch = normalizedBranch,
                        depotKey = depotKey,
                    ))
                    manifests += PreparedCandidate(candidate, depotKey, manifest)
                    val percent = 8 + ((index + 1) * 12 / candidates.size.coerceAtLeast(1))
                    emit(listener, Progress("resolve", percent, "Manifest ${candidate.depotId}: ${manifest.files.size} file(s)"))
                } catch (error: Throwable) {
                    // Some shared depots may be unavailable or unrelated. Keep probing candidates.
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
        val staging = prepareStagingDir(appContext)
        val totalBytes = selected.sumOf { it.manifest.totalRegularBytes { file -> includePayloadFile(file) } }
        val depotsJson = JSONArray()
        var basePercent = 20
        selected.forEachIndexed { index, prepared ->
            control?.throwIfCancelled()
            val depotStart = basePercent
            val depotEnd = if (index == selected.lastIndex) 82 else 20 + ((index + 1) * 62 / selected.size.coerceAtLeast(1))
            emit(listener, Progress("download", depotStart, "Downloading depot ${prepared.candidate.depotId}…", 0L, totalBytes))
            downloader.download(
                request = SteamDepotDirectoryDownloadRequest(
                    appId = prepared.candidate.appId,
                    depotId = prepared.candidate.depotId,
                    manifestId = prepared.candidate.manifestId,
                    branch = normalizedBranch,
                    outputRoot = staging,
                    depotKey = prepared.depotKey,
                    includePredicate = { file -> includePayloadFile(file) },
                ),
                emitProgress = { progress ->
                    val percent = depotStart + ((progress.progressPercent.coerceIn(0, 100) * (depotEnd - depotStart)) / 100)
                    emit(listener, progress.toPayloadProgress(percent))
                },
                waitIfPaused = { control?.throwIfCancelled() },
            )
            basePercent = depotEnd
            depotsJson.put(JSONObject()
                .put("app_id", prepared.candidate.appId.toLong())
                .put("depot_id", prepared.candidate.depotId.toLong())
                .put("manifest_id", prepared.candidate.manifestId.toString())
                .put("branch", normalizedBranch)
                .put("file_count", prepared.manifest.regularFiles().size)
                .put("total_bytes", prepared.manifest.totalRegularBytes()))
        }
        emit(listener, Progress("install", 86, "Installing downloaded payload…"))
        val extras = JSONObject()
            .put("steam", JSONObject()
                .put("app_id", STS2_APP_ID.toLong())
                .put("branch", normalizedBranch)
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

    private fun SteamDepotDirectoryDownloadProgress.toPayloadProgress(percent: Int): Progress = Progress(
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
        downloadedBytes = writtenBytes,
        totalBytes = totalBytes,
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

    private fun prepareStagingDir(context: Context): File {
        val root = File(File(context.filesDir, "steam"), "downloads")
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Unable to create Steam downloads directory: ${root.absolutePath}")
        }
        root.listFiles()?.forEach { child ->
            if (child.name.startsWith("staging-") || child.name.startsWith("failed-")) {
                child.deleteRecursively()
            }
        }
        return File(root, "staging-${UUID.randomUUID()}").also { dir ->
            if (!dir.mkdirs()) {
                throw IOException("Unable to create Steam payload staging directory: ${dir.absolutePath}")
            }
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
