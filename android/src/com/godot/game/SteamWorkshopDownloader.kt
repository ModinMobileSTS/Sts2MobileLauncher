package com.godot.game

import android.content.Context
import com.godot.game.steam.auth.SteamAuthStore
import com.godot.game.steam.auth.SteamLoginCoordinator
import com.godot.game.steam.core.SteamClientIdentity
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import top.apricityx.workshop.workshop.SpireSupplyStationClient
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import kotlinx.serialization.json.Json
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileClient
import top.apricityx.workshop.workshop.DownloadEvent
import top.apricityx.workshop.workshop.DownloadState
import top.apricityx.workshop.workshop.PublishedFileChangeHistoryProvider
import top.apricityx.workshop.workshop.PublishedFileItemInfoProvider
import top.apricityx.workshop.workshop.PublishedFileResolver
import top.apricityx.workshop.workshop.WorkshopDownloadEngine
import top.apricityx.workshop.workshop.WorkshopDownloadRequest
import top.apricityx.workshop.workshop.WorkshopItemResolution
import top.apricityx.workshop.workshop.WorkshopResolvedVariant
import top.apricityx.workshop.workshop.WorkshopVariantCandidate
import top.apricityx.workshop.workshop.WorkshopVariantResolver
import top.apricityx.workshop.workshop.normalizeWorkshopBranch

class SteamWorkshopDownloader(private val context: Context) {
    class CancellationToken {
        private val cancelled = AtomicBoolean(false)

        @Volatile private var job: Job? = null
        fun cancel() {
            cancelled.set(true)
            job?.cancel()
        }

        fun bind(value: Job?) {
            job = value
            if (cancelled.get()) value?.cancel()
        }
        fun throwIfCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                throw IOException("Workshop download cancelled.")
            }
        }
    }

    data class Progress(
        val percent: Int,
        val message: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val indeterminate: Boolean = false,
    )

    data class BranchOption(
        val branch: String,
        val manifestId: String,
        val depotId: String,
        val source: String,
        val fallbackReason: String,
        val matchedBranchMin: String,
        val matchedBranchMax: String,
        val timestampEpochSeconds: Long,
        val title: String,
        val fileSizeBytes: Long,
    )

    data class Result(
        val item: SteamWorkshopCatalog.Item,
        val outputDir: File,
        val branch: String,
        val manifestId: String,
        val depotId: String,
        val resolutionSource: String,
        val fallbackReason: String,
        val matchedBranchMin: String,
        val matchedBranchMax: String,
    )

    class SupplyStationDownloadException(message: String) : IOException(message)

    fun supplyStationOption(): BranchOption = BranchOption(
        SpireSupplyStationClient.BRANCH, "", "", SpireSupplyStationClient.SOURCE,
        context.getString(R.string.workshop_supply_station_branch_notice), "", "", 0L, "", 0L,
    )

    fun loadBranchOptions(item: SteamWorkshopCatalog.Item): List<BranchOption> = runBlocking {
        loadBranchOptions(item, emptyList())
    }

    fun loadBranchOption(item: SteamWorkshopCatalog.Item, branch: String): BranchOption = runBlocking {
        val normalizedBranch = normalizeWorkshopBranch(branch).ifBlank { "public" }
        val options = loadBranchOptions(item, listOf(normalizedBranch))
        selectBranchOption(options, normalizedBranch)
            ?: throw IOException("Unable to resolve Steam Workshop content for branch: $normalizedBranch")
    }

    private suspend fun loadBranchOptions(
        item: SteamWorkshopCatalog.Item,
        extraBranches: Collection<String>,
    ): List<BranchOption> {
        val appContext = context.applicationContext
        val identity = SteamClientIdentity(appContext)
        val client = createWorkshopNetworkClient(appContext)
        val account = createAccountSession(appContext, identity)
        val appId = resolveAppId(item)
        val publishedFileId = item.publishedFileId.toULongOrNull()
            ?: throw IOException("Invalid Steam Workshop file id: ${item.publishedFileId}")
        val directoryClient = SteamDirectoryClient(client)
        val publishedFileClient = SteamPublishedFileClient(
            directoryClient = directoryClient,
            sessionFactory = { identity.createSession(client) },
        )
        val resolver = WorkshopVariantResolver(
            publishedFileResolver = PublishedFileResolver(
                client = client,
                json = Json { ignoreUnknownKeys = true },
                language = "schinese",
            ),
            itemInfoProvider = PublishedFileItemInfoProvider { providerAppId, providerPublishedFileId ->
                publishedFileClient.getItemInfo(account, providerAppId, listOf(providerPublishedFileId))
                    .firstOrNull { it.publishedFileId == providerPublishedFileId }
            },
            changeHistoryProvider = PublishedFileChangeHistoryProvider { providerPublishedFileId ->
                publishedFileClient.getChangeHistory(account, providerPublishedFileId)
            },
        )
        val options = resolver.resolveCandidates(appId, publishedFileId).map { it.toBranchOption() }
        return addDefaultManifestBranchFallbackOptions(appContext, options, extraBranches)
    }

    fun download(
        item: SteamWorkshopCatalog.Item,
        listener: ((Progress) -> Unit)? = null,
        cancellationToken: CancellationToken? = null,
    ): Result = download(item, null, listener, cancellationToken)

    fun download(
        item: SteamWorkshopCatalog.Item,
        selectedOption: BranchOption?,
        listener: ((Progress) -> Unit)? = null,
        cancellationToken: CancellationToken? = null,
    ): Result = runBlocking {
        val appContext = context.applicationContext
        val useSupplyStation = selectedOption?.source == SpireSupplyStationClient.SOURCE ||
            (selectedOption == null && SteamWorkshopPreferences.isSupplyStationEnabled(appContext))
        cancellationToken?.bind(currentCoroutineContext()[Job])
        val identity = SteamClientIdentity(appContext)
        val client = createWorkshopNetworkClient(appContext)
        cancellationToken?.throwIfCancelled()
        val account = if (useSupplyStation) null else createAccountSession(appContext, identity)
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
        val selectedVariant = selectedOption?.toResolvedVariant()
        val branch = if (useSupplyStation) SpireSupplyStationClient.BRANCH else
            normalizeWorkshopBranch(selectedOption?.branch ?: selectedVariant?.branch ?: "public")
        var resolvedResolution: WorkshopItemResolution? = null
        var sawDeterminateProgress = false
        var lastPercent = 0
        var lastMessage = "Resolving workshop item ($branch)..."
        emit(listener, Progress(0, lastMessage, indeterminate = true))
        engine.download(
            WorkshopDownloadRequest(
                appId = resolveAppId(item),
                publishedFileId = item.publishedFileId.toULongOrNull()
                    ?: throw IOException("Invalid Steam Workshop file id: ${item.publishedFileId}"),
                outputDir = outputDir,
                branch = branch,
                selectedVariant = selectedVariant,
                useSupplyStation = useSupplyStation,
            ),
        ).collect { event ->
            cancellationToken?.throwIfCancelled()
            when (event) {
                is DownloadEvent.StateChanged -> {
                    lastMessage = stateLabel(event.state)
                    val pendingState = isPendingDownloadState(event.state)
                    val percent = if (sawDeterminateProgress && pendingState) lastPercent else statePercent(event.state)
                    emit(listener, Progress(
                        percent,
                        lastMessage,
                        indeterminate = !sawDeterminateProgress && pendingState,
                    ))
                }
                is DownloadEvent.Resolved -> {
                    resolvedResolution = event.resolution
                }
                is DownloadEvent.Progress -> {
                    val totalBytes = event.totalBytes
                    val hasKnownTotal = totalBytes != null && totalBytes > 0L
                    val percent = if (hasKnownTotal) {
                        ((event.writtenBytes.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    if (hasKnownTotal) {
                        sawDeterminateProgress = true
                        lastPercent = percent
                    }
                    val label = buildProgressLabel(event, lastMessage)
                    emit(listener, Progress(percent, label, event.writtenBytes, event.totalBytes ?: 0L, indeterminate = !hasKnownTotal))
                }
                is DownloadEvent.FileCompleted -> {
                    sawDeterminateProgress = true
                    lastPercent = 96
                    emit(listener, Progress(lastPercent, event.file.relativePath))
                }
                is DownloadEvent.Completed -> {
                    emit(listener, Progress(100, "Workshop download complete."))
                }
                is DownloadEvent.Failed -> {
                    if (useSupplyStation) throw SupplyStationDownloadException(
                        context.getString(R.string.workshop_supply_station_failure, event.message))
                    throw IOException(event.message)
                }
                is DownloadEvent.LogAppended -> Unit
            }
        }
        val finalResolution = resolvedResolution
        Result(
            item = item,
            outputDir = outputDir,
            branch = branch,
            manifestId = finalResolution?.manifestId?.toString() ?: selectedOption?.manifestId.orEmpty(),
            depotId = finalResolution?.depotId?.toString() ?: selectedOption?.depotId.orEmpty(),
            resolutionSource = finalResolution?.source ?: selectedOption?.source.orEmpty(),
            fallbackReason = finalResolution?.fallbackReason ?: selectedOption?.fallbackReason.orEmpty(),
            matchedBranchMin = finalResolution?.matchedBranchMin ?: selectedOption?.matchedBranchMin.orEmpty(),
            matchedBranchMax = finalResolution?.matchedBranchMax ?: selectedOption?.matchedBranchMax.orEmpty(),
        )
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

    private fun isPendingDownloadState(state: DownloadState): Boolean =
        when (state) {
            DownloadState.Idle,
            DownloadState.Resolving,
            DownloadState.Connecting,
            DownloadState.Downloading,
            DownloadState.Paused -> true
            DownloadState.Success,
            DownloadState.Failed -> false
        }


    private fun addDefaultManifestBranchFallbackOptions(
        context: Context,
        options: List<BranchOption>,
        extraBranches: Collection<String> = emptyList(),
    ): List<BranchOption> {
        val defaultManifestOption = options.firstOrNull { option ->
            option.manifestId.isNotBlank() && option.source == SOURCE_CM_MANIFEST_ID
        } ?: options.firstOrNull { option ->
            option.manifestId.isNotBlank() && option.source == SOURCE_WEBAPI_HCONTENT_FILE
        } ?: options.firstOrNull { option ->
            option.manifestId.isNotBlank()
        } ?: return options

        val branches = linkedSetOf("public")
        branches.addAll(selectedPayloadBranches(context))
        extraBranches.map(::normalizeWorkshopBranch).filter(String::isNotBlank).forEach(branches::add)
        branches.add("public-beta")

        val result = options.toMutableList()
        for (branchValue in branches) {
            val branch = normalizeWorkshopBranch(branchValue)
            if (branch.isBlank()) {
                continue
            }
            val alreadyPresent = result.any { option ->
                normalizeWorkshopBranch(option.branch) == branch &&
                    option.manifestId == defaultManifestOption.manifestId &&
                    option.depotId == defaultManifestOption.depotId
            }
            if (alreadyPresent) {
                continue
            }
            result += defaultManifestOption.copy(
                branch = branch,
                source = SOURCE_BRANCH_DEFAULT_MANIFEST,
                fallbackReason = "no_branch_specific_snapshot_for_branch=$branch; using_default_manifest=${defaultManifestOption.manifestId}",
                matchedBranchMin = "",
                matchedBranchMax = "",
            )
        }
        return result.sortedWith(
            compareBy<BranchOption> { branchOptionSourceRank(it.source) }
                .thenBy { branchOptionSortKey(it.branch) }
                .thenByDescending { it.timestampEpochSeconds }
                .thenBy { it.manifestId },
        )
    }

    private fun selectBranchOption(options: List<BranchOption>, branch: String): BranchOption? {
        val normalizedBranch = normalizeWorkshopBranch(branch).ifBlank { "public" }
        options.firstOrNull { normalizeWorkshopBranch(it.branch) == normalizedBranch }?.let { return it }
        val direct = options.firstOrNull { it.source == SOURCE_DIRECT_FILE_URL } ?: return null
        return direct.copy(
            branch = normalizedBranch,
            fallbackReason = "direct_file_url_ignores_branch=$normalizedBranch",
        )
    }

    private fun selectedPayloadBranches(context: Context): List<String> {
        val payload = runCatching { LaunchProfileManager(context).selectedPayload }.getOrNull() ?: return emptyList()
        val manifest = payload.manifest
        val branches = linkedSetOf<String>()
        manifest.optJSONObject("source")
            ?.optJSONObject("steam")
            ?.optString("branch")
            ?.let(branches::add)
        manifest.optJSONObject("identity")
            ?.optString("branch")
            ?.let(branches::add)
        manifest.optJSONObject("identity")
            ?.optJSONObject("release_info")
            ?.optString("branch")
            ?.let(branches::add)
        manifest.optJSONObject("game")
            ?.optJSONObject("release_info")
            ?.optString("branch")
            ?.let(branches::add)
        if (payload.version.trim().equals("v0.108.0", ignoreCase = true) || payload.version.trim().equals("0.108.0", ignoreCase = true)) {
            branches.add("public-beta")
        }
        return branches.map(::normalizeWorkshopBranch).filter(String::isNotBlank).distinct()
    }

    private fun branchOptionSourceRank(source: String): Int = when (source) {
        SOURCE_AUTHOR_SNAPSHOT -> 0
        SOURCE_CHANGE_HISTORY_SNAPSHOT -> 1
        SOURCE_CM_MANIFEST_ID -> 2
        SOURCE_BRANCH_DEFAULT_MANIFEST -> 3
        SOURCE_WEBAPI_HCONTENT_FILE -> 4
        SOURCE_DIRECT_FILE_URL -> 5
        else -> 9
    }

    private fun branchOptionSortKey(branch: String): Int = when (normalizeWorkshopBranch(branch)) {
        "public" -> 0
        "public-beta" -> 1
        else -> 2
    }

    private fun WorkshopVariantCandidate.toBranchOption(): BranchOption = BranchOption(
        branch = normalizeWorkshopBranch(branch),
        manifestId = manifestId?.toString().orEmpty(),
        depotId = depotId?.toString().orEmpty(),
        source = source,
        fallbackReason = fallbackReason,
        matchedBranchMin = matchedBranchMin,
        matchedBranchMax = matchedBranchMax,
        timestampEpochSeconds = timestampEpochSeconds,
        title = title,
        fileSizeBytes = fileSizeBytes ?: 0L,
    )

    private fun BranchOption.toResolvedVariant(): WorkshopResolvedVariant = WorkshopResolvedVariant(
        branch = normalizeWorkshopBranch(branch),
        manifestId = manifestId.trim().takeIf(String::isNotBlank)?.toULongOrNull(),
        depotId = depotId.trim().takeIf(String::isNotBlank)?.toUIntOrNull(),
        source = source,
        fallbackReason = fallbackReason,
        matchedBranchMin = matchedBranchMin,
        matchedBranchMax = matchedBranchMax,
        timestampEpochSeconds = timestampEpochSeconds,
    )

    private fun resolveAppId(item: SteamWorkshopCatalog.Item): UInt =
        (if (item.appId > 0) item.appId else SteamWorkshopPreferences.DEFAULT_APP_ID).toUInt()

    private fun createAccountSession(context: Context, identity: SteamClientIdentity): SteamAccountSession? {
        val auth = SteamAuthStore.readAuthMaterial(context) ?: return null
        return SteamAccountSession(
            accountName = auth.accountName,
            steamId = authSteamIdOrResolve(context),
            refreshToken = auth.refreshToken,
            machineName = identity.machineName,
        )
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

    private fun authSteamIdOrResolve(context: Context): Long {
        val stored = authSteamIdOrZero(context)
        if (stored > 0L) {
            return stored
        }
        return runCatching {
            SteamLoginCoordinator.verifyRefreshToken(context).trim().toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    private fun createWorkshopNetworkClient(context: Context) =
        SteamWorkshopDirectAccess.buildClient(context) {
            connectTimeout(WORKSHOP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            readTimeout(WORKSHOP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            writeTimeout(WORKSHOP_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            callTimeout(WORKSHOP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            protocols(listOf(Protocol.HTTP_1_1))
            retryOnConnectionFailure(true)
        }

    private fun emit(listener: ((Progress) -> Unit)?, progress: Progress) {
        listener?.invoke(progress)
    }

    companion object {
        // Keep enough headroom for slow regional CDN redirects without leaving a dead route
        // at the fixed 8% connecting state for the older 180-second call timeout.
        private const val WORKSHOP_CONNECT_TIMEOUT_SECONDS = 25L
        private const val WORKSHOP_READ_TIMEOUT_SECONDS = 75L
        private const val WORKSHOP_WRITE_TIMEOUT_SECONDS = 75L
        private const val WORKSHOP_CALL_TIMEOUT_SECONDS = 120L

        private const val SOURCE_AUTHOR_SNAPSHOT = "cm_get_item_info_author_snapshot"
        private const val SOURCE_CHANGE_HISTORY_SNAPSHOT = "cm_get_change_history_snapshot"
        private const val SOURCE_CM_MANIFEST_ID = "cm_get_item_info_manifest_id"
        private const val SOURCE_BRANCH_DEFAULT_MANIFEST = "requested_branch_default_manifest"
        private const val SOURCE_WEBAPI_HCONTENT_FILE = "webapi_hcontent_file"
        private const val SOURCE_DIRECT_FILE_URL = "direct_file_url"
    }
}
