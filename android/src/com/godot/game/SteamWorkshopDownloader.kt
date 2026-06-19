package com.godot.game

import android.content.Context
import com.godot.game.steam.auth.SteamAuthStore
import com.godot.game.steam.core.SteamClientIdentity
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.workshop.DownloadEvent
import top.apricityx.workshop.workshop.DownloadState
import top.apricityx.workshop.workshop.WorkshopDownloadEngine
import top.apricityx.workshop.workshop.WorkshopDownloadRequest

class SteamWorkshopDownloader(private val context: Context) {
    data class Progress(
        val percent: Int,
        val message: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    )

    data class Result(
        val item: SteamWorkshopCatalog.Item,
        val outputDir: File,
    )

    fun download(
        item: SteamWorkshopCatalog.Item,
        listener: ((Progress) -> Unit)? = null,
    ): Result = runBlocking {
        val appContext = context.applicationContext
        val auth = SteamAuthStore.readAuthMaterial(appContext)
        val identity = SteamClientIdentity(appContext)
        val client = createWorkshopNetworkClient(appContext)
        val account = auth?.let {
            SteamAccountSession(
                accountName = it.accountName,
                steamId = authSteamIdOrZero(appContext),
                refreshToken = it.refreshToken,
                machineName = identity.machineName,
            )
        }
        val outputDir = prepareOutputDir(appContext, item.publishedFileId.toString())
        val engine = WorkshopDownloadEngine.createDefault(
            client = client,
            sessionFactory = { identity.createSession(client) },
            sessionConnector = { session, servers ->
                if (account != null && account.steamId > 0L) {
                    session.connectWithRefreshToken(servers, account)
                } else {
                    session.connectAnonymous(servers)
                }
            },
            maxConcurrentChunks = SteamWorkshopPreferences.getConcurrentChunks(appContext),
            allowPublicCdnFallbackOnSessionFailure = true,
            publishedFileLanguage = "schinese",
        )
        var lastMessage = "Resolving workshop item..."
        emit(listener, Progress(1, lastMessage))
        engine.download(
            WorkshopDownloadRequest(
                appId = item.appId.toUInt(),
                publishedFileId = item.publishedFileId.toULongOrNull()
                    ?: throw IOException("Invalid Steam Workshop file id: ${item.publishedFileId}"),
                outputDir = outputDir,
            ),
        ).collect { event ->
            when (event) {
                is DownloadEvent.StateChanged -> {
                    lastMessage = stateLabel(event.state)
                    emit(listener, Progress(statePercent(event.state), lastMessage))
                }
                is DownloadEvent.Progress -> {
                    val totalBytes = event.totalBytes
                    val percent = if (totalBytes != null && totalBytes > 0L) {
                        ((event.writtenBytes.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    val label = buildProgressLabel(event, lastMessage)
                    emit(listener, Progress(percent, label, event.writtenBytes, event.totalBytes ?: 0L))
                }
                is DownloadEvent.FileCompleted -> {
                    emit(listener, Progress(96, event.file.relativePath))
                }
                is DownloadEvent.Completed -> {
                    emit(listener, Progress(100, "Workshop download complete."))
                }
                is DownloadEvent.Failed -> throw IOException(event.message)
                is DownloadEvent.LogAppended -> Unit
            }
        }
        Result(item, outputDir)
    }

    private fun buildProgressLabel(event: DownloadEvent.Progress, fallback: String): String {
        val totalFiles = event.totalFiles
        val files = if (totalFiles != null && totalFiles > 0) {
            " · ${event.completedFiles ?: 0}/$totalFiles files"
        } else {
            ""
        }
        val totalChunks = event.totalChunks
        val chunks = if (totalChunks != null && totalChunks > 0) {
            " · ${event.completedChunks ?: 0}/$totalChunks chunks"
        } else {
            ""
        }
        return fallback + files + chunks
    }

    private fun stateLabel(state: DownloadState): String =
        when (state) {
            DownloadState.Idle -> "Queued."
            DownloadState.Resolving -> "Resolving workshop metadata..."
            DownloadState.Connecting -> "Connecting to Steam..."
            DownloadState.Downloading -> "Downloading workshop files..."
            DownloadState.Paused -> "Paused."
            DownloadState.Success -> "Workshop download complete."
            DownloadState.Failed -> "Workshop download failed."
        }

    private fun statePercent(state: DownloadState): Int =
        when (state) {
            DownloadState.Idle -> 0
            DownloadState.Resolving -> 3
            DownloadState.Connecting -> 8
            DownloadState.Downloading -> 12
            DownloadState.Paused -> 0
            DownloadState.Success -> 100
            DownloadState.Failed -> 0
        }

    private fun prepareOutputDir(context: Context, publishedFileId: String): File {
        val root = File(File(context.filesDir, "workshop"), "downloads")
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Unable to create workshop downloads directory: ${root.absolutePath}")
        }
        return File(root, "$publishedFileId-${UUID.randomUUID()}").also { dir ->
            if (!dir.mkdirs()) {
                throw IOException("Unable to create workshop output directory: ${dir.absolutePath}")
            }
        }
    }

    private fun authSteamIdOrZero(context: Context): Long =
        SteamAuthStore.readSnapshot(context).steamId64.trim().toLongOrNull() ?: 0L

    private fun createWorkshopNetworkClient(context: Context) =
        SteamWorkshopDirectAccess.buildClient(context) {
            connectTimeout(40, TimeUnit.SECONDS)
            readTimeout(90, TimeUnit.SECONDS)
            writeTimeout(90, TimeUnit.SECONDS)
            callTimeout(180, TimeUnit.SECONDS)
            protocols(listOf(Protocol.HTTP_1_1))
            retryOnConnectionFailure(true)
        }

    private fun emit(listener: ((Progress) -> Unit)?, progress: Progress) {
        listener?.invoke(progress)
    }
}
