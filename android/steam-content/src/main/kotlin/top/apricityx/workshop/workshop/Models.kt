package top.apricityx.workshop.workshop

import kotlinx.serialization.Serializable
import java.io.File

data class WorkshopDownloadRequest(
    val appId: UInt,
    val publishedFileId: ULong,
    val outputDir: File,
    val branch: String = "public",
    val selectedVariant: WorkshopResolvedVariant? = null,
    val useSupplyStation: Boolean = false,
)


data class WorkshopResolvedVariant(
    val branch: String,
    val manifestId: ULong?,
    val depotId: UInt?,
    val source: String,
    val fallbackReason: String = "",
    val matchedBranchMin: String = "",
    val matchedBranchMax: String = "",
    val timestampEpochSeconds: Long = 0L,
)

data class WorkshopVariantCandidate(
    val branch: String,
    val manifestId: ULong?,
    val depotId: UInt?,
    val title: String,
    val source: String,
    val fallbackReason: String = "",
    val matchedBranchMin: String = "",
    val matchedBranchMax: String = "",
    val timestampEpochSeconds: Long = 0L,
    val fileSizeBytes: Long? = null,
) {
    fun toResolvedVariant(): WorkshopResolvedVariant = WorkshopResolvedVariant(
        branch = branch,
        manifestId = manifestId,
        depotId = depotId,
        source = source,
        fallbackReason = fallbackReason,
        matchedBranchMin = matchedBranchMin,
        matchedBranchMax = matchedBranchMax,
        timestampEpochSeconds = timestampEpochSeconds,
    )
}


data class WorkshopItemResolution(
    val requestedBranch: String,
    val matchedBranchMin: String = "",
    val matchedBranchMax: String = "",
    val manifestId: ULong? = null,
    val depotId: UInt? = null,
    val source: String,
    val fallbackReason: String = "",
    val timestampEpochSeconds: Long = 0L,
)

@Serializable
enum class DownloadState {
    Idle,
    Resolving,
    Connecting,
    Downloading,
    Paused,
    Success,
    Failed,
}

data class DownloadedFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
)

sealed interface DownloadEvent {
    data class StateChanged(val state: DownloadState) : DownloadEvent
    data class Resolved(val resolution: WorkshopItemResolution) : DownloadEvent
    data class LogAppended(val line: String) : DownloadEvent
    data class Progress(
        val writtenBytes: Long,
        val totalBytes: Long?,
        val completedChunks: Int? = null,
        val totalChunks: Int? = null,
        val completedFiles: Int? = null,
        val totalFiles: Int? = null,
    ) : DownloadEvent
    data class FileCompleted(val file: DownloadedFileInfo) : DownloadEvent
    data class Completed(val files: List<DownloadedFileInfo>) : DownloadEvent
    data class Failed(val message: String) : DownloadEvent
}

sealed interface ResolvedWorkshopItem {
    val title: String
    val metadataJson: String
    val resolution: WorkshopItemResolution

    data class DirectUrlItem(
        val fileName: String,
        val fileUrl: String,
        val size: Long?,
        override val title: String,
        override val metadataJson: String,
        override val resolution: WorkshopItemResolution,
    ) : ResolvedWorkshopItem

    data class UgcManifestItem(
        val manifestId: ULong,
        val depotId: UInt,
        override val title: String,
        override val metadataJson: String,
        override val resolution: WorkshopItemResolution,
    ) : ResolvedWorkshopItem
}

class WorkshopDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
